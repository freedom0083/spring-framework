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
     *
	 * @param bdHolder 自动注入的候选bean
	 * @param descriptor 字段, 方法, 或构造函数所表示的依赖描述的待注入项, 对于多值类型, 这里会是被包装过的包含多元素的MultiElementDescriptor
	 * @return
	 */
	@Override
	// TODO 判断给定的bean(BeanDefinitionHolder)是否为注入项的自动装配候选(可以自动装配到注入项中)
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		// TODO 先用父类GenericTypeAwareAutowireCandidateResolver#isAutowireCandidate(BeanDefinitionHolder, DependencyDescriptor)
		//  检查一下bean是否可以自动装配
		boolean match = super.isAutowireCandidate(bdHolder, descriptor);
		if (match) {
			// TODO 初步验证完发现类型相同, 或者放宽了验证条件时, 就会掉到这里. 这里会检查候选bean是否与待注入项可能存在的@Qualifier
			//  注解所指定value一致. getAnnotations()方法会取得注入项上所有的注解, 只要能走到这里, 待注入项至少会包含@Autowire注解,
			//  所以这个方法至少会 包含一个@Autowire
			match = checkQualifiers(bdHolder, descriptor.getAnnotations());
			if (match) {
				// TODO 如果匹配上了, 再看一下依赖描述的待注入项是否为方法参数(工厂方法, 或构造函数的参数)
				MethodParameter methodParam = descriptor.getMethodParameter();
				if (methodParam != null) {
					// TODO 如果依赖描述的待注入项是方法参数(工厂方法, 或构造函数的参数), 则把使用这个参数的方法拿出来
					Method method = methodParam.getMethod();
					if (method == null || void.class == method.getReturnType()) {
						// TODO 对于构造函数(method == null), 或无返回值的方法来说, 都需要再验证一次. 原因是方法参数(工厂方法,
						//  或构造函数的参数)上可能只标注了@Autowire, 还需要再到使用此参数的方法的方法中检查一下是否有@Qualifier属性.
						//  TIPS: 这里有一点要注意, 因为构造函数, 或方法不一定会标注@Qualifier注解, 所以methodParam.getMethodAnnotations()
						//        有可能会返回空. checkQualifiers(BeanDefinitionHolder, Annotation[])在做匹配验证时, 如果Annotation[]
						//        参数为空, 会自动默认为匹配成功(原因是Spring把他当成Field注解的情况了). 所以在方法上使用@Qualifier注解
						//        时一定要小心
						match = checkQualifiers(bdHolder, methodParam.getMethodAnnotations());
					}
				}
			}
		}
		return match;
	}

	/**
	 * Match the given qualifier annotations against the candidate bean definition.
	 *
	 * @param bdHolder 候选bean
	 * @param annotationsToSearch 注入项上所有的注解, 比如: @Autowire, @Qualifier等
	 * @return 候选bean是否匹配由限定注解(@Qualifier)所指定的限定条件
	 */
	// TODO 候选bean是否匹配由限定注解(@Qualifier)所指定的限定条件
	protected boolean checkQualifiers(BeanDefinitionHolder bdHolder, Annotation[] annotationsToSearch) {
		if (ObjectUtils.isEmpty(annotationsToSearch)) {
			// TODO 待注入项可以是field, 或method其中之一. 如果是filed, 那method就会为空, getMethodAnnotations()方法也不会有值
			//  整个流程先处理的field的情况. 只要field匹配上了, 就不必再处理method了, 所以直接返回true
			return true;
		}
		SimpleTypeConverter typeConverter = new SimpleTypeConverter();
		for (Annotation annotation : annotationsToSearch) {
			// TODO 遍历所有待查的注解, 取得每个注解的type类型, 比如@Autowire, @Qualifier, @LoadBalanced等
			Class<? extends Annotation> type = annotation.annotationType();
			// TODO 是否检查元注解
			boolean checkMeta = true;
			// TODO 是否要回退到元注解上
			boolean fallbackToMeta = false;
			// TODO 判断注解类型是否为支持的限定注解类型, 默认是@Qualifier或JSR-330的javax.inject.Qualifier, 也可以指定自定义类型.
			//  TIPS: 假如当前正在验证的注解是@LoadBanlanced时, 即: org.springframework.cloud.client.loadbalancer.LoadBalanced时,
			//  这里也会返回true(@LoadBanlanced内包含@Qualifier)
			if (isQualifier(type)) {
				// TODO 当注入项上的注解是限定条件注解类型时, 会用checkQualifier()方法来验证注解限定符所指定的bean是否与候选bean匹配, 比如:
				//  1. 当前是@Qualifier注解: 候选bean有可能会与@Qualifier的value所指定的bean匹配, 所以有可能是true, 也有可能是false
				//  2. 当前是@LoadBanlanced注解: 无法匹配, 肯定会返回false, 然后退回到@LoadBanlanced注解本身去看其元注解是否包含限定注解
				if (!checkQualifier(bdHolder, annotation, typeConverter)) {
					// TODO 不匹配时, 回退到元注解上接着验证
					fallbackToMeta = true;
				}
				else {
					// TODO 匹配时, 就不用再到元注解上验证了
					checkMeta = false;
				}
			}
			if (checkMeta) {
				// TODO 前面没有匹配上时, 会回退到当前注解, 对其元注解进行检查检查. 比如@LoadBanlanced, 其本身不满足限定条件,
				//  但@Qualifier为其元注解, 所以需要对元注解进行检查, 来确实其是否包含@Qualifier. 这个过程只进行一次, 如果包了
				//  一层以上, 就没用了
				boolean foundMeta = false;
				for (Annotation metaAnn : type.getAnnotations()) {
					// TODO 遍历注解上所有的元注解, 比如@LoadBalanced会标注了@Documented, @Retention, @Target, @Qualifier等
					Class<? extends Annotation> metaType = metaAnn.annotationType();
					if (isQualifier(metaType)) {
						// TODO 只要找到了@Qualifier, 就标识查找成功
						foundMeta = true;
						// Only accept fallback match if @Qualifier annotation has a value...
						// Otherwise it is just a marker for a custom qualifier annotation.
						// TODO 但只有@Qualifier也不行, 其必须指定一个value值, 且指定的值与候选bean相同, 所以还要进行一下检查
						if ((fallbackToMeta && ObjectUtils.isEmpty(AnnotationUtils.getValue(metaAnn))) ||
								!checkQualifier(bdHolder, metaAnn, typeConverter)) {
							// TODO 在需要检查元注解时, 如果@Qualifier没有value, 或者候选bean与@Qualifier指定的value不同时, 表示匹配失败
							return false;
						}
					}
				}
				if (fallbackToMeta && !foundMeta) {
					// TODO 回退到元注解也没找到时, 表示匹配失败
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
	 * @param annotation 待注入项上包含的注解, 对于限定条件来说, 是@Qualifier或JSR-330的javax.inject.Qualifier
	 * @param typeConverter 类型转换器
	 * @return 候选bean是否匹配由限定注解(@Qualifier)所指定的限定条件
	 */
	// TODO 根据候选bean来匹配由Annotation给定的限定注释(@Qualifier)
	protected boolean checkQualifier(
			BeanDefinitionHolder bdHolder, Annotation annotation, TypeConverter typeConverter) {
		// TODO 取得注解的type类型(一般为org.springframework.beans.factory.annotation.Qualifier, 或javax.inject.Qualifier)
		Class<? extends Annotation> type = annotation.annotationType();
		// TODO 拿到候选bean的bd
		RootBeanDefinition bd = (RootBeanDefinition) bdHolder.getBeanDefinition();
		// TODO 在bean注册到容器中时, 由容器进行设置:
		//  1. xml形式配置: 会在解析配置文件节点时进行解析, 将所有<qualifier />元素解析后, 包装为AutowireCandidateQualifier放到bd中
		//  2. 注解形式配置: AnnotatedBeanDefinitionReader#doRegisterBean(Class<T>, @Nullable String,
		//                      @Nullable Class<? extends Annotation>[],
		//                      @Nullable Supplier<T>, Nullable BeanDefinitionCustomizer[])中确实存在对于 Class<? extends Annotation>[]
		//              的处理, 但实际上并没有使用带有qualifier参数地方, 这里一直是null. 如果是注解配置, 是无法取得bd中的AutowireCandidateQualifier
		AutowireCandidateQualifier qualifier = bd.getQualifier(type.getName());
		if (qualifier == null) {
			// TODO 对于xml配置来说, 如果用注解的全限定名没找到对应的AutowireCandidateQualifier时, 尝试用短名'Qualifier'进行查找,
			//  此处同样对于注解形式的配置无效
			qualifier = bd.getQualifier(ClassUtils.getShortName(type));
		}
		if (qualifier == null) {
			// First, check annotation on qualified element, if any
			// TODO 还没拿到, 看一下候选bean的注解元素中是否有@Qualifier注解, 此处依然只针对xml形式配置, 注解形式配置得到的还是null
			Annotation targetAnnotation = getQualifiedElementAnnotation(bd, type);
			// Then, check annotation on factory method, if applicable
			if (targetAnnotation == null) {
				// TODO 还没有, 看一下候选bean的工厂方法上是否有@Qualifier注解
				targetAnnotation = getFactoryMethodAnnotation(bd, type);
			}
			if (targetAnnotation == null) {
				// TODO 还没有, 看候选bean是不是一个代理类, 如果是, 看看其代理目标类的工厂方法上是否有@Qualifier注解
				RootBeanDefinition dbd = getResolvedDecoratedDefinition(bd);
				if (dbd != null) {
					targetAnnotation = getFactoryMethodAnnotation(dbd, type);
				}
			}
			if (targetAnnotation == null) {
				// Look for matching annotation on the target class
				// TODO 还没有, 就看看自身了, 比如: @LoadBalanced标注在RestTemplate上
				if (getBeanFactory() != null) {
					try {
						// TODO 从当前容器里找候选bean对应的类型
						Class<?> beanType = getBeanFactory().getType(bdHolder.getBeanName());
						if (beanType != null) {
							// TODO 找到的话, 看看这个类型上是否有@Qualifier注解
							targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(beanType), type);
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						// Not the usual case - simply forget about the type check...
					}
				}
				if (targetAnnotation == null && bd.hasBeanClass()) {
					// TODO 还没有, 就看一下候选bean的class属性所指定的class上是否有@Qualifier注解
					targetAnnotation = AnnotationUtils.getAnnotation(ClassUtils.getUserClass(bd.getBeanClass()), type);
				}
			}
			if (targetAnnotation != null && targetAnnotation.equals(annotation)) {
				// TODO 找到@Qualifier注解时, 如果此注解与待注入项上包含的注解完全相同, 即value值相同时, 才表示匹配成功
				return true;
			}
		}
		// TODO targetAnnotation没有找到时, 把@Qualifier注解的属性全都拿出来继续尝试
		Map<String, Object> attributes = AnnotationUtils.getAnnotationAttributes(annotation);
		if (attributes.isEmpty() && qualifier == null) {
			// If no attributes, the qualifier must be present
			// TODO 没有属性, 也没有bd中也没有AutowireCandidateQualifier时(只在xml形式配置中才有), 匹配失败
			return false;
		}
		for (Map.Entry<String, Object> entry : attributes.entrySet()) {
			// TODO 迭代注解的属性, @Qualifier只有value属性, 所以只会进来一次. 看其他限定注解, 可能会有其他属性
			String attributeName = entry.getKey();
			Object expectedValue = entry.getValue();
			Object actualValue = null;
			// Check qualifier first
			if (qualifier != null) {
				// TODO 在有AutowireCandidateQualifier时, 用@Qualifier注解的'value'到AutowireCandidateQualifier中取对应的值做为实际值
				actualValue = qualifier.getAttribute(attributeName);
			}
			if (actualValue == null) {
				// Fall back on bean definition attribute
				// TODO 没有从AutowireCandidateQualifier取得实际值时, 回退到候选bean, 看其是否设置了与属性值相同的值
				actualValue = bd.getAttribute(attributeName);
			}
			if (actualValue == null && attributeName.equals(AutowireCandidateQualifier.VALUE_KEY) &&
					expectedValue instanceof String && bdHolder.matchesName((String) expectedValue)) {
				// Fall back on bean name (or alias) match
				// TODO 还是没有实际值, 且当前的属性是'value', 其值是与候选bean同名时, 迭代下一个属性
				continue;
			}
			if (actualValue == null && qualifier != null) {
				// Fall back on default, but only if the qualifier is present
				// TODO 没有得到实际值, 但有AutowireCandidateQualifier时, 从当前注解中拿属性对应的值
				actualValue = AnnotationUtils.getDefaultValue(annotation, attributeName);
			}
			if (actualValue != null) {
				// TODO 如果拿到了, 根据情况进行转型
				actualValue = typeConverter.convertIfNecessary(actualValue, expectedValue.getClass());
			}
			// TODO 属性中的设定值与实际值会进行判断, 只有完全一致的, 才算匹配成功
			if (!expectedValue.equals(actualValue)) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	protected Annotation getQualifiedElementAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		// TODO 从bd中拿到已注解的元素, 如果有, 根据type类型从注解元素中返回对应的注解, 没有则返回null
		AnnotatedElement qualifiedElement = bd.getQualifiedElement();
		return (qualifiedElement != null ? AnnotationUtils.getAnnotation(qualifiedElement, type) : null);
	}

	@Nullable
	protected Annotation getFactoryMethodAnnotation(RootBeanDefinition bd, Class<? extends Annotation> type) {
		// TODO 从bd中拿工厂方法, 如果有, 根据type类型从工厂方法中返回对应的注解, 没有则返回null
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
	// TODO 取得依赖描述的待注入项的@Value注解中的value
	public Object getSuggestedValue(DependencyDescriptor descriptor) {
		// TODO 先取得依赖描述的待注入项(可能是个字段, 或者方法)的所有注解, 然后取得其中的@Value的值
		Object value = findValue(descriptor.getAnnotations());
		if (value == null) {
			// TODO 如果没有取到, 则这个依赖描述的待注入项可能是方法参数(工厂方法, 或构造函数的参数)
			MethodParameter methodParam = descriptor.getMethodParameter();
			if (methodParam != null) {
				// TODO 如果真的是方法参数(工厂方法, 或构造函数的参数), 则尝试从其方法(构造函数, 或工厂方法)上的注解入手, 看看有没有@Value注解取得
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
			// TODO 拿到@Value注解
			AnnotationAttributes attr = AnnotatedElementUtils.getMergedAnnotationAttributes(
					AnnotatedElementUtils.forAnnotations(annotationsToSearch), this.valueAnnotationType);
			if (attr != null) {
				// TODO 提取其中的值
				return extractValue(attr);
			}
		}
		return null;
	}

	/**
	 * Extract the value attribute from the given annotation.
	 * @since 4.3
	 */
	// TODO 从注解中提取value内容
	protected Object extractValue(AnnotationAttributes attr) {
		Object value = attr.get(AnnotationUtils.VALUE);
		if (value == null) {
			throw new IllegalStateException("Value annotation must have a value attribute");
		}
		return value;
	}

}
