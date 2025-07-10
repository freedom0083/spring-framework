/*
 * Copyright 2002-present the original author or authors.
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.AjType;
import org.aspectj.lang.reflect.AjTypeSystem;
import org.aspectj.lang.reflect.PerClauseKind;
import org.jspecify.annotations.Nullable;

import org.springframework.aop.framework.AopConfigException;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.SpringProperties;
import org.springframework.core.annotation.AnnotationUtils;

/**
 * Abstract base class for factories that can create Spring AOP Advisors
 * given AspectJ classes from classes honoring the AspectJ 5 annotation syntax.
 *
 * <p>This class handles annotation parsing and validation functionality.
 * It does not actually generate Spring AOP Advisors, which is deferred to subclasses.
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 2.0
 */
// TODO 这个工厂的做用是从AspectJ 5注解的类创建Spring AOP的Advisor. 提供了Spring AOP与AspectJ注解之间的映射关系, 支持部分AspectJ
//  的注解: @Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing
public abstract class AbstractAspectJAdvisorFactory implements AspectJAdvisorFactory {

	// TODO Spring所支持的AspectJ的所有注解
	private static final Class<?>[] ASPECTJ_ANNOTATION_CLASSES = new Class<?>[] {
			Pointcut.class, Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class};

	private static final String AJC_MAGIC = "ajc$";

	/**
	 * System property that instructs Spring to ignore ajc-compiled aspects
	 * for Spring AOP proxying, restoring traditional Spring behavior for
	 * scenarios where both weaving and AspectJ auto-proxying are enabled.
	 * <p>The default is "false". Consider switching this to "true" if you
	 * encounter double execution of your aspects in a given build setup.
	 * Note that we recommend restructuring your AspectJ configuration to
	 * avoid such double exposure of an AspectJ aspect to begin with.
	 * @since 6.1.15
	 */
	public static final String IGNORE_AJC_PROPERTY_NAME = "spring.aop.ajc.ignore";

	private static final boolean shouldIgnoreAjcCompiledAspects =
			SpringProperties.getFlag(IGNORE_AJC_PROPERTY_NAME);


	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());
	// TODO 提供了默认的参数名探测器AspectJAnnotationParameterNameDiscoverer
	protected final ParameterNameDiscoverer parameterNameDiscoverer = new AspectJAnnotationParameterNameDiscoverer();


	// TODO 当前类是否为一个切面. 判断标准是, 其是否被@Aspect注解, 且还没有被AspectJ编译过
	@Override
	public boolean isAspect(Class<?> clazz) {
		return (AnnotationUtils.findAnnotation(clazz, Aspect.class) != null &&
				(!shouldIgnoreAjcCompiledAspects || !compiledByAjc(clazz)));
	}

	@Override
	public void validate(Class<?> aspectClass) throws AopConfigException {
		AjType<?> ajType = AjTypeSystem.getAjType(aspectClass);
		if (!ajType.isAspect()) {
			// TODO 切面可能会没有@Aspect注解么??
			throw new NotAnAtAspectException(aspectClass);
		}
		// TODO 目前Spring AOP是不支持AspectJ的PERCFLOW和PERCFLOWBELOW这两种切面实例的
		if (ajType.getPerClause().getKind() == PerClauseKind.PERCFLOW) {
			// TODO Aspect不允许扩展一个具体的切面, 所以当@Aspect注解的切面的父类也被@Aspect注解, 且其父类不为抽象类时, 就会抛出异常:
			//  [Subclass] cannot extend concrete aspect [Superclass]
			throw new AopConfigException(aspectClass.getName() + " uses percflow instantiation model: " +
					"This is not supported in Spring AOP.");
		}
		if (ajType.getPerClause().getKind() == PerClauseKind.PERCFLOWBELOW) {
			// TODO Aspect不允许扩展一个具体的切面, 所以当@Aspect注解的切面的父类也被@Aspect注解, 且其父类不为抽象类时, 就会抛出异常:
			//  [Subclass] cannot extend concrete aspect [Superclass]
			throw new AopConfigException(aspectClass.getName() + " uses percflowbelow instantiation model: " +
					"This is not supported in Spring AOP.");
		}
	}


	/**
	 * Find and return the first AspectJ annotation on the given method
	 * (there <i>should</i> only be one anyway...).
	 */
	@SuppressWarnings("unchecked")
	// TODO 获取Advice方法上的注解, 即: @Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing. 并将其包装成一
	//  个AspectJAnnotation
	protected static @Nullable AspectJAnnotation findAspectJAnnotationOnMethod(Method method) {
		for (Class<?> annotationType : ASPECTJ_ANNOTATION_CLASSES) {
			// TODO 按顺序遍历Spring AOP所支持的所有用于方法上的注解, 即: @Pointcut, @Around, @Before, @After, @AfterReturning,
			//  @AfterThrowing. 看方法上是否包含其中一个注解. 并会对注解中的表达式进行解析, 然后生成一个AspectJAnnotation
			AspectJAnnotation annotation = findAnnotation(method, (Class<Annotation>) annotationType);
			if (annotation != null) {
				// TODO 这是个断路操作, 按顺序找, 只要找到一个就行
				return annotation;
			}
		}
		return null;
	}

	private static @Nullable AspectJAnnotation findAnnotation(Method method, Class<? extends Annotation> annotationType) {
		Annotation annotation = AnnotationUtils.findAnnotation(method, annotationType);
		if (annotation != null) {
			// TODO 如果找到, 则包装成AspectJAnnotation并返回. 这个过程会解析确定注解类型, 解析注解表达式中'pointcut', 'value'属性, 以及设置参数名
			return new AspectJAnnotation(annotation);
		}
		else {
			return null;
		}
	}

	private static boolean compiledByAjc(Class<?> clazz) {
		for (Field field : clazz.getDeclaredFields()) {
			if (field.getName().startsWith(AJC_MAGIC)) {
				return true;
			}
		}
		return false;
	}


	/**
	 * Enum for AspectJ annotation types.
	 * @see AspectJAnnotation#getAnnotationType()
	 */
	protected enum AspectJAnnotationType {

		AtPointcut, AtAround, AtBefore, AtAfter, AtAfterReturning, AtAfterThrowing
	}


	/**
	 * Class modeling an AspectJ annotation, exposing its type enumeration and
	 * pointcut String.
	 */
	protected static class AspectJAnnotation {
		// TODO 注解中表达式内的属性
		private static final String[] EXPRESSION_ATTRIBUTES = {"pointcut", "value"};
		// TODO 定义了Spring AOP支持的注解与AspectJ支持的注解之间的映射
		private static final Map<Class<?>, AspectJAnnotationType> annotationTypeMap = Map.of(
				Pointcut.class, AspectJAnnotationType.AtPointcut, //
				Around.class, AspectJAnnotationType.AtAround, //
				Before.class, AspectJAnnotationType.AtBefore, //
				After.class, AspectJAnnotationType.AtAfter, //
				AfterReturning.class, AspectJAnnotationType.AtAfterReturning, //
				AfterThrowing.class, AspectJAnnotationType.AtAfterThrowing //
			);

		private final Annotation annotation;
		// TODO AspectJ定义的注解的类型
		private final AspectJAnnotationType annotationType;
		// TODO 注解中的切点表达式
		private final String pointcutExpression;

		private final String argumentNames;

		public AspectJAnnotation(Annotation annotation) {
			this.annotation = annotation;
			// TODO 确定注解的AspectJ类型, 只能是@Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing其中之一, 否则就抛异常了
			this.annotationType = determineAnnotationType(annotation);
			try {
				// TODO 解析注解中的表达式中的'pointcut', 'value'属性
				this.pointcutExpression = resolvePointcutExpression(annotation);
				// TODO 取得并设置参数名
				Object argNames = AnnotationUtils.getValue(annotation, "argNames");
				this.argumentNames = (argNames instanceof String names ? names : "");
			}
			catch (Exception ex) {
				throw new IllegalArgumentException(annotation + " is not a valid AspectJ annotation", ex);
			}
		}

		// TODO 确定注解的类型
		private AspectJAnnotationType determineAnnotationType(Annotation annotation) {
			// TODO Spring AOP只支持AspectJ的@Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing这些注解,
			//  其他注解会抛出'Unknown annotation type'异常
			AspectJAnnotationType type = annotationTypeMap.get(annotation.annotationType());
			if (type != null) {
				return type;
			}
			throw new IllegalStateException("Unknown annotation type: " + annotation);
		}

		// TODO 解析注解表达式中的'pointcut', 'value'属性
		private String resolvePointcutExpression(Annotation annotation) {
			for (String attributeName : EXPRESSION_ATTRIBUTES) {
				Object val = AnnotationUtils.getValue(annotation, attributeName);
				if (val instanceof String str && !str.isEmpty()) {
					// TODO 断路操作, 只要存在一个就把值拿出来
					return str;
				}
			}
			throw new IllegalStateException("Failed to resolve pointcut expression in: " + annotation);
		}

		public AspectJAnnotationType getAnnotationType() {
			return this.annotationType;
		}

		public Annotation getAnnotation() {
			return this.annotation;
		}

		public String getPointcutExpression() {
			return this.pointcutExpression;
		}

		public String getArgumentNames() {
			return this.argumentNames;
		}

		@Override
		public String toString() {
			return this.annotation.toString();
		}
	}


	/**
	 * ParameterNameDiscoverer implementation that analyzes the arg names
	 * specified at the AspectJ annotation level.
	 */
	// TODO 这个名字探测器主要做的就是拆分'argNames'属性中的字符串, 然后返回拆分后的数组
	private static class AspectJAnnotationParameterNameDiscoverer implements ParameterNameDiscoverer {

		private static final String[] EMPTY_ARRAY = new String[0];

		@Override
		public String @Nullable [] getParameterNames(Method method) {
			if (method.getParameterCount() == 0) {
				return EMPTY_ARRAY;
			}
			// TODO 取得Advice方法上的注解
			AspectJAnnotation annotation = findAspectJAnnotationOnMethod(method);
			if (annotation == null) {
				return null;
			}
			// TODO 拿出注解中'argNames'属性值, 然后按','切分. 把切分后的结果, 转成数组后返回. 其实就是用StringTokenizer进行字符串拆分,
			StringTokenizer nameTokens = new StringTokenizer(annotation.getArgumentNames(), ",");
			int numTokens = nameTokens.countTokens();
			if (numTokens > 0) {
				String[] names = new String[numTokens];
				for (int i = 0; i < names.length; i++) {
					names[i] = nameTokens.nextToken();
				}
				return names;
			}
			else {
				return null;
			}
		}

		@Override
		public @Nullable String @Nullable [] getParameterNames(Constructor<?> ctor) {
			// TODO 不支持构造器的Advice
			throw new UnsupportedOperationException("Spring AOP cannot handle constructor advice");
		}
	}

}
