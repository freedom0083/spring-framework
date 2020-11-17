/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop.aspectj.annotation;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.DeclareParents;
import org.aspectj.lang.annotation.Pointcut;

import org.springframework.aop.Advisor;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;
import org.springframework.aop.aspectj.DeclareParentsAdvisor;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConvertingComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.util.StringUtils;
import org.springframework.util.comparator.InstanceComparator;

/**
 * Factory that can create Spring AOP Advisors given AspectJ classes from
 * classes honoring AspectJ's annotation syntax, using reflection to invoke the
 * corresponding advice methods.
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 2.0
 */
@SuppressWarnings("serial")
public class ReflectiveAspectJAdvisorFactory extends AbstractAspectJAdvisorFactory implements Serializable {

	// Exclude @Pointcut methods
	private static final MethodFilter adviceMethodFilter = ReflectionUtils.USER_DECLARED_METHODS
			.and(method -> (AnnotationUtils.getAnnotation(method, Pointcut.class) == null));

	// TODO 比较器, 先按@Around -> @Before -> @After -> @AfterReturning -> @AfterThrowing排序, 然后再按名字排
	private static final Comparator<Method> adviceMethodComparator;

	static {
		// Note: although @After is ordered before @AfterReturning and @AfterThrowing,
		// an @After advice method will actually be invoked after @AfterReturning and
		// @AfterThrowing methods due to the fact that AspectJAfterAdvice.invoke(MethodInvocation)
		// invokes proceed() in a `try` block and only invokes the @After advice method
		// in a corresponding `finally` block.
		Comparator<Method> adviceKindComparator = new ConvertingComparator<>(
				new InstanceComparator<>(
						Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class),
				(Converter<Method, Annotation>) method -> {
					AspectJAnnotation<?> ann = AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(method);
					return (ann != null ? ann.getAnnotation() : null);
				});
		Comparator<Method> methodNameComparator = new ConvertingComparator<>(Method::getName);
		adviceMethodComparator = adviceKindComparator.thenComparing(methodNameComparator);
	}


	@Nullable
	private final BeanFactory beanFactory;


	/**
	 * Create a new {@code ReflectiveAspectJAdvisorFactory}.
	 */
	public ReflectiveAspectJAdvisorFactory() {
		this(null);
	}

	/**
	 * Create a new {@code ReflectiveAspectJAdvisorFactory}, propagating the given
	 * {@link BeanFactory} to the created {@link AspectJExpressionPointcut} instances,
	 * for bean pointcut handling as well as consistent {@link ClassLoader} resolution.
	 * @param beanFactory the BeanFactory to propagate (may be {@code null}}
	 * @since 4.3.6
	 * @see AspectJExpressionPointcut#setBeanFactory
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#getBeanClassLoader()
	 */
	public ReflectiveAspectJAdvisorFactory(@Nullable BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	/**
	 * 这里会为@Aspect切面里由@Around, @Before, @After, @AfterReturning, @AfterThrowing标注的所有方法, 以及被@DeclareParents
	 * 注解标注的字段创建Advisor
	 * @param aspectInstanceFactory the aspect instance factory
	 * (not the aspect instance itself in order to avoid eager instantiation)
	 * @return 为切面所创建的所有的Advisor
	 */
	@Override
	public List<Advisor> getAdvisors(MetadataAwareAspectInstanceFactory aspectInstanceFactory) {
		// TODO 先取得@Aspect切面所在的Class, 以及Class的名字
		Class<?> aspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
		String aspectName = aspectInstanceFactory.getAspectMetadata().getAspectName();
		// TODO 对切面进行一下检查, 保证满足AspectJ的要求, 并且还得是Spring AOP所支持的切面实例类型
		validate(aspectClass);

		// We need to wrap the MetadataAwareAspectInstanceFactory with a decorator
		// so that it will only instantiate once.
		// TODO 对工厂进行包装, 使其成为一个具有懒加载功能的单例工厂, 为的是让其只实例化一次
		MetadataAwareAspectInstanceFactory lazySingletonAspectInstanceFactory =
				new LazySingletonAspectInstanceFactoryDecorator(aspectInstanceFactory);

		List<Advisor> advisors = new ArrayList<>();
		// TODO 通过getAdvisorMethods()取得@Aspect标注的切面中, 由@Around, @Before, @After, @AfterReturning, @AfterThrowing
		//  标注的所有方法(不包含@Pointcut注解标注的方法)
		for (Method method : getAdvisorMethods(aspectClass)) {
			// Prior to Spring Framework 5.2.7, advisors.size() was supplied as the declarationOrderInAspect
			// to getAdvisor(...) to represent the "current position" in the declared methods list.
			// However, since Java 7 the "current position" is not valid since the JDK no longer
			// returns declared methods in the order in which they are declared in the source code.
			// Thus, we now hard code the declarationOrderInAspect to 0 for all advice methods
			// discovered via reflection in order to support reliable advice ordering across JVM launches.
			// Specifically, a value of 0 aligns with the default value used in
			// AspectJPrecedenceComparator.getAspectDeclarationOrder(Advisor).
			// TODO 遍历每个由注解标注的方法, 对注解进行解析, 用解析结果创建一个包含了表达式的Pointcut切点. 然后用切点, 注解类型,
			//  注解方法等创建Advisor, 并加入到Advisor结果集中
			Advisor advisor = getAdvisor(method, lazySingletonAspectInstanceFactory, 0, aspectName);
			if (advisor != null) {
				advisors.add(advisor);
			}
		}

		// If it's a per target aspect, emit the dummy instantiating aspect.
		if (!advisors.isEmpty() && lazySingletonAspectInstanceFactory.getAspectMetadata().isLazilyInstantiated()) {
			// TODO 对于支持懒加载的情况, 需要在所有的Advisor之前加一个同步实例化Advisor
			Advisor instantiationAdvisor = new SyntheticInstantiationAdvisor(lazySingletonAspectInstanceFactory);
			advisors.add(0, instantiationAdvisor);
		}

		// Find introduction fields.
		// TODO 上面是处理方法的, 下面开始处理字段上的@DeclareParents注解了. @DeclareParents注解是用来为现在的类增加新方法用的
		for (Field field : aspectClass.getDeclaredFields()) {
			// TODO @DeclareParents可以为类增加新的方法, 如果有字段用@DeclareParents进行了注解, 则对其内容进行解析('value'和
			//  'defaultImpl'属性值), 然后创建一个新的Advisor, 并回到返回结果集中
			Advisor advisor = getDeclareParentsAdvisor(field);
			if (advisor != null) {
				advisors.add(advisor);
			}
		}

		return advisors;
	}

	// TODO 取得切面里所有除了@Pointcut标注的方法以外的其他方法(被@Around, @Before, @After, @AfterReturning, @AfterThrowing标注的方法),
	private List<Method> getAdvisorMethods(Class<?> aspectClass) {
		List<Method> methods = new ArrayList<>();
		// TODO 从@Aspect注解标注的切面中过滤掉所有非桥接, 非合成的方法, 然后再把除了@Pointcut标注的方法以外的其他方法全部放到切面
		//  要使用的方法集合中. (被@Around, @Before, @After, @AfterReturning, @AfterThrowing标注的方法. 然后再按@Around ->
		//  @Before -> @After -> @AfterReturning -> @AfterThrowing的顺序, 以及方法名字排序)
		ReflectionUtils.doWithMethods(aspectClass, methods::add, adviceMethodFilter);
		if (methods.size() > 1) {
			// TODO 这里会排除掉@Pointcut注解的方法
			methods.sort(adviceMethodComparator);
		}
		return methods;
	}

	/**
	 * Build a {@link org.springframework.aop.aspectj.DeclareParentsAdvisor}
	 * for the given introduction field.
	 * <p>Resulting Advisors will need to be evaluated for targets.
	 * @param introductionField the field to introspect
	 * @return the Advisor instance, or {@code null} if not an Advisor
	 */
	@Nullable
	private Advisor getDeclareParentsAdvisor(Field introductionField) {
		// TODO 取得字段上的@DeclareParents注解
		DeclareParents declareParents = introductionField.getAnnotation(DeclareParents.class);
		if (declareParents == null) {
			// Not an introduction field
			return null;
		}

		if (DeclareParents.class == declareParents.defaultImpl()) {
			// TODO 对于@DeclareParents注解来说, 必需要设置'defaultImpl'属性
			throw new IllegalStateException("'defaultImpl' attribute must be set on DeclareParents");
		}
		// TODO 然后用@DeclareParents注解的字段的类型, 以及@DeclareParents注解中的value和defaultImpl设置的值创建一个用于处理
		//  @DeclareParents注解的Advisor并返回
		return new DeclareParentsAdvisor(
				introductionField.getType(), declareParents.value(), declareParents.defaultImpl());
	}

	/**
	 *
	 * @param candidateAdviceMethod the candidate advice method @Aspect切面中被@Around, @Before, @After, @AfterReturning
	 *                              , @AfterThrowing这些注解标注的方法
	 * @param aspectInstanceFactory the aspect instance factory
	 * @param declarationOrderInAspect 当前方法在切面中的位置(Advice标注过的方法都是排过序的, 就是这个)
	 * @param aspectName the name of the aspect @Aspect切面所标注的Class的名字
	 * @return InstantiationModelAwarePointcutAdvisorImpl实例, 切面中的每个方法都会创建一个
	 */
	@Override
	@Nullable
	public Advisor getAdvisor(Method candidateAdviceMethod, MetadataAwareAspectInstanceFactory aspectInstanceFactory,
			int declarationOrderInAspect, String aspectName) {
		// TODO 验证一下切面, 保证其满足AspectJ的要求, 并且还得是Spring AOP所支持的切面实例类型
		validate(aspectInstanceFactory.getAspectMetadata().getAspectClass());
		// TODO 为注解方法创建一个解析了注解中表达式的AspectJExpressionPointcut
		AspectJExpressionPointcut expressionPointcut = getPointcut(
				candidateAdviceMethod, aspectInstanceFactory.getAspectMetadata().getAspectClass());
		if (expressionPointcut == null) {
			return null;
		}
		// TODO 创建一个包含了上面创建的切点的Advisor实现InstantiationModelAwarePointcutAdvisorImpl. 此实现类会为@Advice切面中
		//  被注解的每个方法创建一个Advisor实例. 在其内部会创建一个PerTargetInstantiationModelPointcut类型的Pointcut
		return new InstantiationModelAwarePointcutAdvisorImpl(expressionPointcut, candidateAdviceMethod,
				this, aspectInstanceFactory, declarationOrderInAspect, aspectName);
	}

	/**
	 * 为@Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing注解创建一个AspectJExpressionPointcut.
	 * 创建过程中会解析注解中的表达式
	 * @param candidateAdviceMethod 要进行通知操作的方法
	 * @param candidateAspectClass 被@Aspect注解的切面Class
	 * @return 根据注解的方法所创建出来的表达式切点
	 */
	@Nullable
	private AspectJExpressionPointcut getPointcut(Method candidateAdviceMethod, Class<?> candidateAspectClass) {
		// TODO 取得切点按顺序遍历Spring AOP所支持的所有用于方法上的注解, 即: @Pointcut, @Around, @Before, @After, @AfterReturning,
		//  @AfterThrowing. 看方法上是否包含其中一个注解. 对于@Around, @Before, @After, @AfterReturning来说, 取的是其表达式中'pointcut', 'value'属性值
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		if (aspectJAnnotation == null) {
			return null;
		}
		// TODO 创建一个支持AspectJ表达式的切点, 同时设置解析好的表达式
		AspectJExpressionPointcut ajexp =
				new AspectJExpressionPointcut(candidateAspectClass, new String[0], new Class<?>[0]);
		ajexp.setExpression(aspectJAnnotation.getPointcutExpression());
		if (this.beanFactory != null) {
			// TODO 为切点设置容器
			ajexp.setBeanFactory(this.beanFactory);
		}
		return ajexp;
	}


	/**
	 *
	 * @param candidateAdviceMethod the candidate advice method 要进行增强操作的方法
	 * @param expressionPointcut the AspectJ expression pointcut AspectJ的表达式切点
	 * @param aspectInstanceFactory the aspect instance factory  Aspect实例工厂
	 * @param declarationOrder the declaration order within the aspect 当前方法在切面中的位置(Advice标注过的方法都是排过序的, 就是这个)
	 * @param aspectName the name of the aspect @Aspect切面所标注的Class的名字
	 * @return
	 */
	@Override
	@Nullable
	public Advice getAdvice(Method candidateAdviceMethod, AspectJExpressionPointcut expressionPointcut,
			MetadataAwareAspectInstanceFactory aspectInstanceFactory, int declarationOrder, String aspectName) {
		// TODO 取得切面, 然后对其进行验证, 保证其满足AspectJ的要求, 并且还得是Spring AOP所支持的切面实例类型
		Class<?> candidateAspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
		validate(candidateAspectClass);
		// TODO 取得Advice方法上的注解. 会对注解中的表达式进行解析
		AspectJAnnotation<?> aspectJAnnotation =
				AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
		if (aspectJAnnotation == null) {
			return null;
		}

		// If we get here, we know we have an AspectJ method.
		// Check that it's an AspectJ-annotated class
		if (!isAspect(candidateAspectClass)) {
			// TODO 无法处理非AspectJ-annotated的类
			throw new AopConfigException("Advice must be declared inside an aspect type: " +
					"Offending method '" + candidateAdviceMethod + "' in class [" +
					candidateAspectClass.getName() + "]");
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Found AspectJ method: " + candidateAdviceMethod);
		}

		AbstractAspectJAdvice springAdvice;
		// TODO 下面就是处理Spring AOP支持的几种Advice了
		switch (aspectJAnnotation.getAnnotationType()) {
			case AtPointcut:
				// TODO Pointcut
				if (logger.isDebugEnabled()) {
					logger.debug("Processing pointcut '" + candidateAdviceMethod.getName() + "'");
				}
				return null;
			case AtAround:
				// TODO 用于环绕方法的Advice
				springAdvice = new AspectJAroundAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
			case AtBefore:
				// TODO 用于方法前执行的Advice
				springAdvice = new AspectJMethodBeforeAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
			case AtAfter:
				// TODO 用于方法后执行的Advice
				springAdvice = new AspectJAfterAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				break;
			case AtAfterReturning:
				// TODO 用于方法返回后执行的Advice
				springAdvice = new AspectJAfterReturningAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				AfterReturning afterReturningAnnotation = (AfterReturning) aspectJAnnotation.getAnnotation();
				if (StringUtils.hasText(afterReturningAnnotation.returning())) {
					springAdvice.setReturningName(afterReturningAnnotation.returning());
				}
				break;
			case AtAfterThrowing:
				// TODO 用于抛出异常后执行的Advice
				springAdvice = new AspectJAfterThrowingAdvice(
						candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
				AfterThrowing afterThrowingAnnotation = (AfterThrowing) aspectJAnnotation.getAnnotation();
				if (StringUtils.hasText(afterThrowingAnnotation.throwing())) {
					springAdvice.setThrowingName(afterThrowingAnnotation.throwing());
				}
				break;
			default:
				// TODO 其他的就全不支持了
				throw new UnsupportedOperationException(
						"Unsupported advice type on method: " + candidateAdviceMethod);
		}

		// Now to configure the advice...
		// TODO 设置Advice的信息, 名字, 位置, 参数这些
		springAdvice.setAspectName(aspectName);
		springAdvice.setDeclarationOrder(declarationOrder);
		// TODO 根据Advice方法使用AspectJAnnotationParameterNameDiscoverer取得参数名数组
		String[] argNames = this.parameterNameDiscoverer.getParameterNames(candidateAdviceMethod);
		if (argNames != null) {
			// TODO 如果注解中设置了'argNames'值, 则对Advice进行参数设置. 当解析过的参数数量比Advice的方法中的参数多一个时, 则表示
			//  可能有个隐式连接点. 这时会判断Advice的方法中第一个参数, 如果是JoinPoint, ProceedingJoinPoint, 或者JoinPoint$StaticPart时,
			//  会在解析过的参数数组首位增加一个'THIS_JOIN_POINT'参数
			springAdvice.setArgumentNamesFromStringArray(argNames);
		}
		// TODO 进行参数绑定, 主要是设置切点的需要的参数名, 以及参数类型
		springAdvice.calculateArgumentBindings();

		return springAdvice;
	}


	/**
	 * Synthetic advisor that instantiates the aspect.
	 * Triggered by per-clause pointcut on non-singleton aspect.
	 * The advice has no effect.
	 */
	@SuppressWarnings("serial")
	protected static class SyntheticInstantiationAdvisor extends DefaultPointcutAdvisor {

		public SyntheticInstantiationAdvisor(final MetadataAwareAspectInstanceFactory aif) {
			super(aif.getAspectMetadata().getPerClausePointcut(), (MethodBeforeAdvice)
					(method, args, target) -> aif.getAspectInstance());
		}
	}

}
