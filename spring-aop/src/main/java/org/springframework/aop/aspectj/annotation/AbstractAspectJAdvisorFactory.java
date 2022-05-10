/*
 * Copyright 2002-2021 the original author or authors.
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
import java.lang.reflect.Modifier;
import java.util.HashMap;
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

import org.springframework.aop.framework.AopConfigException;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;

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
 * @since 2.0
 */
// TODO 这个工厂的做用是从AspectJ 5注解的类创建Spring AOP的Advisor. 提供了Spring AOP与AspectJ注解之间的映射关系, 支持部分AspectJ
//  的注解: @Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing
public abstract class AbstractAspectJAdvisorFactory implements AspectJAdvisorFactory {

	private static final String AJC_MAGIC = "ajc$";
	// TODO Spring所支持的AspectJ的所有注解
	private static final Class<?>[] ASPECTJ_ANNOTATION_CLASSES = new Class<?>[] {
			Pointcut.class, Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class};


	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());
	// TODO 提供了默认的参数名探测器AspectJAnnotationParameterNameDiscoverer
	protected final ParameterNameDiscoverer parameterNameDiscoverer = new AspectJAnnotationParameterNameDiscoverer();


	/**
	 * We consider something to be an AspectJ aspect suitable for use by the Spring AOP system
	 * if it has the @Aspect annotation, and was not compiled by ajc. The reason for this latter test
	 * is that aspects written in the code-style (AspectJ language) also have the annotation present
	 * when compiled by ajc with the -1.5 flag, yet they cannot be consumed by Spring AOP.
	 */
	// TODO 当前类是否为一个切面. 判断标准是, 其是否被@Aspect注解, 且还没有被AspectJ编译过
	@Override
	public boolean isAspect(Class<?> clazz) {
		return (hasAspectAnnotation(clazz) && !compiledByAjc(clazz));
	}

	// TODO 是否为一个被@Aspect注解的切面
	private boolean hasAspectAnnotation(Class<?> clazz) {
		return (AnnotationUtils.findAnnotation(clazz, Aspect.class) != null);
	}

	/**
	 * We need to detect this as "code-style" AspectJ aspects should not be
	 * interpreted by Spring AOP.
	 */
	private boolean compiledByAjc(Class<?> clazz) {
		// The AJTypeSystem goes to great lengths to provide a uniform appearance between code-style and
		// annotation-style aspects. Therefore there is no 'clean' way to tell them apart. Here we rely on
		// an implementation detail of the AspectJ compiler.
		for (Field field : clazz.getDeclaredFields()) {
			// TODO 被AspectJ编译过的类, 其字段都是以'ajc$'开头的, 这些类是不能被Spring AOP进行处理的
			if (field.getName().startsWith(AJC_MAGIC)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void validate(Class<?> aspectClass) throws AopConfigException {
		// If the parent has the annotation and isn't abstract it's an error
		Class<?> superclass = aspectClass.getSuperclass();
		if (superclass.getAnnotation(Aspect.class) != null &&
				!Modifier.isAbstract(superclass.getModifiers())) {
			// TODO Aspect不允许扩展一个具体的切面, 所以当@Aspect注解的切面的父类也被@Aspect注解, 且其父类不为抽象类时, 就会抛出异常:
			//  [Subclass] cannot extend concrete aspect [Superclass]
			throw new AopConfigException("[" + aspectClass.getName() + "] cannot extend concrete aspect [" +
					superclass.getName() + "]");
		}

		AjType<?> ajType = AjTypeSystem.getAjType(aspectClass);
		if (!ajType.isAspect()) {
			// TODO 切面可能会没有@Aspect注解么??
			throw new NotAnAtAspectException(aspectClass);
		}
		// TODO 目前Spring AOP是不支持AspectJ的PERCFLOW和PERCFLOWBELOW这两种切面实例的
		if (ajType.getPerClause().getKind() == PerClauseKind.PERCFLOW) {
			throw new AopConfigException(aspectClass.getName() + " uses percflow instantiation model: " +
					"This is not supported in Spring AOP.");
		}
		if (ajType.getPerClause().getKind() == PerClauseKind.PERCFLOWBELOW) {
			throw new AopConfigException(aspectClass.getName() + " uses percflowbelow instantiation model: " +
					"This is not supported in Spring AOP.");
		}
	}

	/**
	 * Find and return the first AspectJ annotation on the given method
	 * (there <i>should</i> only be one anyway...).
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	// TODO 获取Advice方法上的注解, 即: @Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing. 并将其包装成一
	//  个AspectJAnnotation
	protected static AspectJAnnotation<?> findAspectJAnnotationOnMethod(Method method) {
		for (Class<?> clazz : ASPECTJ_ANNOTATION_CLASSES) {
			// TODO 按顺序遍历Spring AOP所支持的所有用于方法上的注解, 即: @Pointcut, @Around, @Before, @After, @AfterReturning,
			//  @AfterThrowing. 看方法上是否包含其中一个注解. 并会对注解中的表达式进行解析, 然后生成一个AspectJAnnotation
			AspectJAnnotation<?> foundAnnotation = findAnnotation(method, (Class<Annotation>) clazz);
			if (foundAnnotation != null) {
				// TODO 这是个断路操作, 按顺序找, 只要找到一个就行
				return foundAnnotation;
			}
		}
		return null;
	}

	@Nullable
	private static <A extends Annotation> AspectJAnnotation<A> findAnnotation(Method method, Class<A> toLookFor) {
		A result = AnnotationUtils.findAnnotation(method, toLookFor);
		if (result != null) {
			// TODO 如果找到, 则包装成AspectJAnnotation并返回. 这个过程会解析确定注解类型, 解析注解表达式中'pointcut', 'value'属性, 以及设置参数名
			return new AspectJAnnotation<>(result);
		}
		else {
			return null;
		}
	}


	/**
	 * Enum for AspectJ annotation types.
	 * @see AspectJAnnotation#getAnnotationType()
	 */
	protected enum AspectJAnnotationType {

		AtPointcut, AtAround, AtBefore, AtAfter, AtAfterReturning, AtAfterThrowing
	}


	/**
	 * Class modelling an AspectJ annotation, exposing its type enumeration and
	 * pointcut String.
	 * @param <A> the annotation type
	 */
	protected static class AspectJAnnotation<A extends Annotation> {
		// TODO 注解中表达式内的属性
		private static final String[] EXPRESSION_ATTRIBUTES = new String[] {"pointcut", "value"};
		// TODO 定义了Spring AOP支持的注解与AspectJ支持的注解之间的映射
		private static Map<Class<?>, AspectJAnnotationType> annotationTypeMap = new HashMap<>(8);

		static {
			annotationTypeMap.put(Pointcut.class, AspectJAnnotationType.AtPointcut);
			annotationTypeMap.put(Around.class, AspectJAnnotationType.AtAround);
			annotationTypeMap.put(Before.class, AspectJAnnotationType.AtBefore);
			annotationTypeMap.put(After.class, AspectJAnnotationType.AtAfter);
			annotationTypeMap.put(AfterReturning.class, AspectJAnnotationType.AtAfterReturning);
			annotationTypeMap.put(AfterThrowing.class, AspectJAnnotationType.AtAfterThrowing);
		}

		private final A annotation;
		// TODO AspectJ定义的注解的类型
		private final AspectJAnnotationType annotationType;
		// TODO 注解中的切点表达式
		private final String pointcutExpression;

		private final String argumentNames;

		public AspectJAnnotation(A annotation) {
			this.annotation = annotation;
			// TODO 确定注解的AspectJ类型, 只能是@Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing其中之一, 否则就抛异常了
			this.annotationType = determineAnnotationType(annotation);
			try {
				// TODO 解析注解中的表达式中的'pointcut', 'value'属性
				this.pointcutExpression = resolveExpression(annotation);
				// TODO 取得并设置参数名
				Object argNames = AnnotationUtils.getValue(annotation, "argNames");
				this.argumentNames = (argNames instanceof String ? (String) argNames : "");
			}
			catch (Exception ex) {
				throw new IllegalArgumentException(annotation + " is not a valid AspectJ annotation", ex);
			}
		}

		// TODO 确定注解的类型
		private AspectJAnnotationType determineAnnotationType(A annotation) {
			// TODO Spring AOP只支持AspectJ的@Pointcut, @Around, @Before, @After, @AfterReturning, @AfterThrowing这些注解,
			//  其他注解会抛出'Unknown annotation type'异常
			AspectJAnnotationType type = annotationTypeMap.get(annotation.annotationType());
			if (type != null) {
				return type;
			}
			throw new IllegalStateException("Unknown annotation type: " + annotation);
		}

		// TODO 解析注解表达式中的'pointcut', 'value'属性
		private String resolveExpression(A annotation) {
			for (String attributeName : EXPRESSION_ATTRIBUTES) {
				Object val = AnnotationUtils.getValue(annotation, attributeName);
				if (val instanceof String str) {
					if (!str.isEmpty()) {
						// TODO 断路操作, 只要存在一个就把值拿出来
						return str;
					}
				}
			}
			throw new IllegalStateException("Failed to resolve expression: " + annotation);
		}

		public AspectJAnnotationType getAnnotationType() {
			return this.annotationType;
		}

		public A getAnnotation() {
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

		@Override
		@Nullable
		public String[] getParameterNames(Method method) {
			if (method.getParameterCount() == 0) {
				return new String[0];
			}
			// TODO 取得Advice方法上的注解
			AspectJAnnotation<?> annotation = findAspectJAnnotationOnMethod(method);
			if (annotation == null) {
				return null;
			}
			// TODO 拿出注解中'argNames'属性值, 然后按','切分. 把切分后的结果, 转成数组后返回. 其实就是用StringTokenizer进行字符串拆分,
			StringTokenizer nameTokens = new StringTokenizer(annotation.getArgumentNames(), ",");
			if (nameTokens.countTokens() > 0) {
				String[] names = new String[nameTokens.countTokens()];
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
		@Nullable
		public String[] getParameterNames(Constructor<?> ctor) {
			// TODO 不支持构造器的Advice
			throw new UnsupportedOperationException("Spring AOP cannot handle constructor advice");
		}
	}

}
