/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.beans.factory.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.GenericTypeAwareAutowireCandidateResolver;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * {@link AutowireCandidateResolver} implementation that matches bean definition qualifiers
 * against {@link Qualifier qualifier annotations} on the field or parameter to be autowired.
 * Also supports suggested expression values through a {@link Value value} annotation.
 *
 * <p>Also supports JSR-330's {@link javax.inject.Qualifier} annotation, if available.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 2.5
 * @see AutowireCandidateQualifier
 * @see Qualifier
 * @see Value
 */
public class QualifierAnnotationAutowireCandidateResolver extends GenericTypeAwareAutowireCandidateResolver {
	// TODO 可用的限定类型, 初始化时默认指定了@Qualifier和JSR-330的javax.inject.Qualifier(不支持时就没有这个)
	private final Set<Class<? extends Annotation>> qualifierTypes = new LinkedHashSet<>(2);
	// TODO @Value注解
	private Class<? extends Annotation> valueAnnotationType = Value.class;


	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for Spring's standard {@link Qualifier} annotation.
	 * <p>Also supports JSR-330's {@link javax.inject.Qualifier} annotation, if available.
	 */
	@SuppressWarnings("unchecked")
	public QualifierAnnotationAutowireCandidateResolver() {
		// TODO 注册Spring的Qualifier
		this.qualifierTypes.add(Qualifier.class);
		try {
			// TODO 注册JSR-330的javax.inject.Qualifier
			this.qualifierTypes.add((Class<? extends Annotation>) ClassUtils.forName("javax.inject.Qualifier",
							QualifierAnnotationAutowireCandidateResolver.class.getClassLoader()));
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
		}
	}

	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for the given qualifier annotation type.
	 * @param qualifierType the qualifier annotation to look for
	 */
	// TODO 注册一个自定义的qualifier注解
	public QualifierAnnotationAutowireCandidateResolver(Class<? extends Annotation> qualifierType) {
		Assert.notNull(qualifierType, "'qualifierType' must not be null");
		this.qualifierTypes.add(qualifierType);
	}

	/**
	 * Create a new QualifierAnnotationAutowireCandidateResolver
	 * for the given qualifier annotation types.
	 * @param qualifierTypes the qualifier annotations to look for
	 */
	// TODO 注册一个自定义的qualifier注解集合
	public QualifierAnnotationAutowireCandidateResolver(Set<Class<? extends Annotation>> qualifierTypes) {
		Assert.notNull(qualifierTypes, "'qualifierTypes' must not be null");
		this.qualifierTypes.addAll(qualifierTypes);
	}


	/**
	 * Register the given type to be used as a qualifier when autowiring.
	 * <p>This identifies qualifier annotations for direct use (on fields,
	 * method parameters and constructor parameters) as well as meta
	 * annotations that in turn identify actual qualifier annotations.
	 * <p>This implementation only supports annotations as qualifier types.
	 * The default is Spring's {@link Qualifier} annotation which serves
	 * as a qualifier for direct use and also as a meta annotation.
	 * @param qualifierType the annotation type to register
	 */
	// TODO 用于注册自定义的qualifier注解
	public void addQualifierType(Class<? extends Annotation> qualifierType) {
		this.qualifierTypes.add(qualifierType);
	}

	/**
	 * Set the 'value' annotation type, to be used on fields, method parameters
	 * and constructor parameters.
	 * <p>The default value annotation type is the Spring-provided
	 * {@link Value} annotation.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate a default value
	 * expression for a specific argument.
	 */
	// TODO 自定义@Value注解
	public void setValueAnnotationType(Class<? extends Annotation> valueAnnotationType) {
		this.valueAnnotationType = valueAnnotationType;
	}


	/**
	 * Determine whether the provided bean definition is an autowire candidate.
	 * <p>To be considered a candidate the bean's <em>autowire-candidate</em>
	 * attribute must not have been set to 'false'. Also, if an annotation on
	 * the field or parameter to be autowired is recognized by this bean factory
	 * as a <em>qualifier</em>, the bean must 'match' against the annotation as
	 * well as any attributes it may contain. The bean definition must contain
	 * the same qualifier or match by meta attributes. A "value" attribute will
	 * fallback to match against the bean name or an alias if a qualifier or
	 * attribute does not match.
	 * @see Qualifier
	 */
	@Override
	// TODO 用于判断bean是否可以被自动装配
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		// TODO 先用父类GenericTypeAwareAutowireCandidateResolver#isAutowireCandidate(BeanDefinitionHolder, DependencyDescriptor)
		//  检查一下bean是否可以自动装配
		boolean match = super.isAutowireCandidate(bdHolder, descriptor);
		if (match) {
			// TODO 如果可以自动装配, 检查候选bean是否匹配注入项上限定注解所指定的限定条件(descriptor.getAnnotations()会包含注入项上的所有注解)
			match = checkQualifiers(bdHolder, descriptor.getAnnotations());
			if (match) {
				// TODO 如果匹配
				MethodParameter methodParam = descriptor.getMethodParameter();
				if (methodParam != null) {
					Method method = methodParam.getMethod();
					if (method == null || void.class == method.getReturnType()) {
						match = checkQualifiers(bdHolder, methodParam.getMethodAnnotations());
					}
				}
			}
		}
		return match;
	}

	/**
	 * Match the given qualifier annotations against the candidate bean definition.
	 * @param bdHolder 候选bean
	 * @param annotationsToSearch 注入项上所有的注解, 比如: @Autowire, @Qualifier等
	 * @return 候选bean是否匹配由限定注解(@Qualifier)所指定的限定条件
	 */
	// TODO 检查候选bean是否匹配由限定注解所指定的限定条件
	protected boolean checkQualifiers(BeanDefinitionHolder bdHolder, Annotation[] annotationsToSearch) {
		if (ObjectUtils.isEmpty(annotationsToSearch)) {
			// TODO 注入项没有注解时, 不用处理, 直接返回true
			return true;
		}
		SimpleTypeConverter typeConverter = new SimpleTypeConverter();
		for (Annotation annotation : annotationsToSearch) {
			// TODO 遍历所有待查的注解, 取得每个注解的type类型, 比如Autowire, Qualifier这些
			Class<? extends Annotation> type = annotation.annotationType();
			// TODO 是否检查元注解
			boolean checkMeta = true;
			// TODO 是否要回退到元注解上
			boolean fallbackToMeta = false;
			// TODO 判断注解类型是否为支持的限定注解类型, 默认是@Qualifier或JSR-330的javax.inject.Qualifier
			if (isQualifier(type)) {
				// TODO 当注入项上的注解是限定条件注解类型时, 验证一下是否满足限定条件
				if (!checkQualifier(bdHolder, annotation, typeConverter)) {
					// TODO 不满足时, 回退到元注解上接着验证
					fallbackToMeta = true;
				}
				else {
					// TODO 满足时, 就不用再到元注解上验证了
					checkMeta = false;
				}
			}
			if (checkMeta) {
				boolean foundMeta = false;
				for (Annotation metaAnn : type.getAnnotations()) {
					// TODO 遍历注解上所有的元注解, 比如@LoadBalanced会标注了@Documented, @Retention, @Target, @Qualifier等
					Class<? extends Annotation> metaType = metaAnn.annotationType();
					if (isQualifier(metaType)) {
						// TODO 只要找到了@Qualifier注解, 就标识查找成功
						foundMeta = true;
						// Only accept fallback match if @Qualifier annotation has a value...
						// Otherwise it is just a marker for a custom qualifier annotation.
						if ((fallbackToMeta && StringUtils.isEmpty(AnnotationUtils.getValue(metaAnn))) ||
								!checkQualifier(bdHolder, metaAnn, typeConverter)) {
							return false;
						}
					}
				}
				if (fallbackToMeta && !foundMeta) {
					// TODO 回退到元注解也没找到时, 返回匹配失败
					return false;
				}
			}
		}
		// TODO 候选bean完全匹配由限定注解所指定的限定条件时, 表示候选bean是合法的
		return true;
	}

	/**
	 * Checks whether the given annotation type is a recognized qualifier type.
	 */
	protected boolean isQualifier(Class<? extends Annotation> annotationType) {
		for (Class<? extends Annotation> qualifierType : this.qualifierTypes) {
			if (annotationType.equals(qualifierType) || annotationType.isAnnotationPresent(qualifierType)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Match the given qualifier annotation against the candidate bean definition.
	 * @param bdHolder 注入候选bean
	 * @param annotation 注入项中包含的注解, 对于限定条件来说, 是@Qualifier或JSR-330的javax.inject.Qualifier
	 * @param typeConverter 类型转换器
	 * @return 候选bean是否匹配由限定注解(@Qualifier)所指定的限定条件
	 */
	// TODO 根据候选bean来匹配由Annotation给定的限定注释
	protected boolean checkQualifier(
			BeanDefinitionHolder bdHolder, Annotation annotation, TypeConverter typeConverter) {
		// TODO 取得注解的type类型(Spring或JSR-330的Qualifier)
		Class<? extends Annotation> type = annotation.annotationType();
		// TODO 拿到候选bean的bd
		RootBeanDefinition bd = (RootBeanDefinition) bdHolder.getBeanDefinition();
		// TODO AnnotationConfigApplicationContext容器在注册bean时会解析@Qualifier注解, 将其包装为AutowireCandidateQualifier
		//  放到bd的qualifiers缓存中, 这里先去缓存中用@Qualifier的type类型(全限定名, 即org.springframework.beans.factory.annotation.Qualifier)
		//  找其对应的AutowireCandidateQualifier
		AutowireCandidateQualifier qualifier = bd.getQualifier(type.getName());
		if (qualifier == null) {
			// TODO 如果用全限定名没找到, 再尝试用类名Qualifier到候选bean的缓存查找
			qualifier = bd.getQualifier(ClassUtils.getShortName(type));
		}
		if (qualifier == null) {
			// First, check annotation on qualified element, if any
			// TODO 还没拿到, 看一下候选bean的AnnotatedElement中是否有由Annotation(@Qualifier类型)所指定的注解
			Annotation targetAnnotation = getQualifiedElementAnnotation(bd, type);
			// Then, check annotation on factory method, if applicable
			if (targetAnnotation == null) {
				// TODO 还没有, 看候选bean的工厂方法中是否有由Annotation(@Qualifier类型)所指定的注解
				targetAnnotation = getFactoryMethodAnnotation(bd, type);
			}
			if (targetAnnotation == null) {
				// TODO 还没有, 看候选bean是不是一个代理类, 如果是, 看看其目标类的工厂方法中是否有由Annotation(@Qualifier类型)所指定的注解
				RootBeanDefinition dbd = getResolvedDecoratedDefinition(bd);
				if (dbd != null) {
					targetAnnotation = getFactoryMethodAnnotation(dbd, type);
				}
			}
			if (targetAnnotation == null) {
				// Look for matching annotation on the target class
				// TODO 还没有, 就看看容器
				if (getBeanFactory() != null) {
					try {
						// TODO 容器里要是有候选bean对应的类型, 看看这个类型上是否有由Annotation(@Qualifier类型)所指定的注解
						Class<?> beanType = getBeanFactory().getType(bdHolder.getBeanName());
						if (beanType != null) {
							targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(beanType), type);
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						// Not the usual case - simply forget about the type check...
					}
				}
				if (targetAnnotation == null && bd.hasBeanClass()) {
					// TODO 还没有, 就看一下候选bean的class属性所指定的class上是否有由Annotation(@Qualifier类型)所指定的注解
					targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(bd.getBeanClass()), type);
				}
			}
			if (targetAnnotation != null && targetAnnotation.equals(annotation)) {
				// TODO 找到, 并且是指定的注解时(@Qualifier), 返回true
				return true;
			}
		}
		// TODO 依然没有找到@Qualifier候选时, 把注解的属性全拿出来
		Map<String, Object> attributes = AnnotationUtils.getAnnotationAttributes(annotation);
		if (attributes.isEmpty() && qualifier == null) {
			// If no attributes, the qualifier must be present
			// TODO 没有属性, 也没有@Qualifier候选时, 表示候选bean不是Qualifier候选
			return false;
		}
		for (Map.Entry<String, Object> entry : attributes.entrySet()) {
			String attributeName = entry.getKey();
			Object expectedValue = entry.getValue();
			Object actualValue = null;
			// Check qualifier first
			// TODO 挨个儿检查注解中的属性
			if (qualifier != null) {
				actualValue = qualifier.getAttribute(attributeName);
			}
			if (actualValue == null) {
				// Fall back on bean definition attribute
				actualValue = bd.getAttribute(attributeName);
			}
			if (actualValue == null && attributeName.equals(AutowireCandidateQualifier.VALUE_KEY) &&
					expectedValue instanceof String && bdHolder.matchesName((String) expectedValue)) {
				// Fall back on bean name (or alias) match
				continue;
			}
			if (actualValue == null && qualifier != null) {
				// Fall back on default, but only if the qualifier is present
				actualValue = AnnotationUtils.getDefaultValue(annotation, attributeName);
			}
			if (actualValue != null) {
				actualValue = typeConverter.convertIfNecessary(actualValue, expectedValue.getClass());
			}
			if (!expectedValue.equals(actualValue)) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	protected Annotation getQualifiedElementAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		AnnotatedElement qualifiedElement = bd.getQualifiedElement();
		return (qualifiedElement != null ? AnnotationUtils.getAnnotation(qualifiedElement, type) : null);
	}

	@Nullable
	protected Annotation getFactoryMethodAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		Method resolvedFactoryMethod = bd.getResolvedFactoryMethod();
		return (resolvedFactoryMethod != null ? AnnotationUtils.getAnnotation(resolvedFactoryMethod, type) : null);
	}


	/**
	 * Determine whether the given dependency declares an autowired annotation,
	 * checking its required flag.
	 * @see Autowired#required()
	 */
	@Override
	public boolean isRequired(DependencyDescriptor descriptor) {
		if (!super.isRequired(descriptor)) {
			return false;
		}
		Autowired autowired = descriptor.getAnnotation(Autowired.class);
		return (autowired == null || autowired.required());
	}

	/**
	 * Determine whether the given dependency declares a qualifier annotation.
	 * @see #isQualifier(Class)
	 * @see Qualifier
	 */
	@Override
	public boolean hasQualifier(DependencyDescriptor descriptor) {
		for (Annotation ann : descriptor.getAnnotations()) {
			if (isQualifier(ann.annotationType())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether the given dependency declares a value annotation.
	 * @see Value
	 */
	@Override
	@Nullable
	public Object getSuggestedValue(DependencyDescriptor descriptor) {
		// TODO 先取得依赖注入项的所有注解, 然后取得其中的@Value的值
		Object value = findValue(descriptor.getAnnotations());
		if (value == null) {
			MethodParameter methodParam = descriptor.getMethodParameter();
			if (methodParam != null) {
				// TODO 没取得注解内的值时, 尝试从依赖注入项的方法的参数取得
				value = findValue(methodParam.getMethodAnnotations());
			}
		}
		return value;
	}

	/**
	 * Determine a suggested value from any of the given candidate annotations.
	 */
	@Nullable
	protected Object findValue(Annotation[] annotationsToSearch) {
		if (annotationsToSearch.length > 0) {   // qualifier annotations have to be local
			AnnotationAttributes attr = AnnotatedElementUtils.getMergedAnnotationAttributes(
					AnnotatedElementUtils.forAnnotations(annotationsToSearch), this.valueAnnotationType);
			if (attr != null) {
				return extractValue(attr);
			}
		}
		return null;
	}

	/**
	 * Extract the value attribute from the given annotation.
	 * @since 4.3
	 */
	protected Object extractValue(AnnotationAttributes attr) {
		Object value = attr.get(AnnotationUtils.VALUE);
		if (value == null) {
			throw new IllegalStateException("Value annotation must have a value attribute");
		}
		return value;
	}

}
