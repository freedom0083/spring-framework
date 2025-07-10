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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.aot.generate.AccessControl;
import org.springframework.aot.generate.GeneratedClass;
import org.springframework.aot.generate.GeneratedMethod;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.support.ClassHintUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.aot.AutowiredArgumentsCodeGenerator;
import org.springframework.beans.factory.aot.AutowiredFieldValueResolver;
import org.springframework.beans.factory.aot.AutowiredMethodArgumentsResolver;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.aot.CodeWarnings;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.javapoet.ClassName;
import org.springframework.javapoet.CodeBlock;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor}
 * implementation that autowires annotated fields, setter methods, and arbitrary
 * config methods. Such members to be injected are detected through annotations:
 * by default, Spring's {@link Autowired @Autowired} and {@link Value @Value}
 * annotations.
 *
 * <p>Also supports the common {@link jakarta.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with
 * the 'required' attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the 'required' attribute
 * is set to {@code true}, only a single constructor may be annotated with
 * {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. If a class only
 * declares a single constructor to begin with, it will always be used, even if not
 * annotated. An annotated constructor does not have to be public.
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * <h3>Annotation Config vs. XML Config</h3>
 * <p>A default {@code AutowiredAnnotationBeanPostProcessor} will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom {@code AutowiredAnnotationBeanPostProcessor} bean definition.
 *
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * <h3>{@literal @}Lookup Methods</h3>
 * <p>In addition to regular injection points as discussed above, this post-processor
 * also handles Spring's {@link Lookup @Lookup} annotation which identifies lookup
 * methods to be replaced by the container at runtime. This is essentially a type-safe
 * version of {@code getBean(Class, args)} and {@code getBean(String, args)}.
 * See {@link Lookup @Lookup's javadoc} for details.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 2.5
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 */
public class AutowiredAnnotationBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor,
		MergedBeanDefinitionPostProcessor, BeanRegistrationAotProcessor, PriorityOrdered, BeanFactoryAware {

	private static final Constructor<?>[] EMPTY_CONSTRUCTOR_ARRAY = new Constructor<?>[0];


	protected final Log logger = LogFactory.getLog(getClass());

	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes = CollectionUtils.newLinkedHashSet(4);

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	private @Nullable ConfigurableListableBeanFactory beanFactory;

	private @Nullable MetadataReaderFactory metadataReaderFactory;

	private final Set<String> lookupMethodsChecked = ConcurrentHashMap.newKeySet(256);

	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache = new ConcurrentHashMap<>(256);

	private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new {@code AutowiredAnnotationBeanPostProcessor} for Spring's
	 * standard {@link Autowired @Autowired} and {@link Value @Value} annotations.
	 * <p>Also supports the common {@link jakarta.inject.Inject @Inject} annotation
	 * if available.
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		// TODO 标识可以解析的注解, 分别是@Autowired, @Value, 以及JSR-330中的@Inject
		this.autowiredAnnotationTypes.add(Autowired.class);
		this.autowiredAnnotationTypes.add(Value.class);

		ClassLoader classLoader = AutowiredAnnotationBeanPostProcessor.class.getClassLoader();
		try {
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("jakarta.inject.Inject", classLoader));
			logger.trace("'jakarta.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// jakarta.inject API not available - simply skip.
		}
	}


	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as the common {@code @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as the common {@code @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of an attribute of the annotation that specifies whether it is required.
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required.
	 * <p>For example if using 'required=true' (the default), this value should be
	 * {@code true}; but if using 'optional=false', this value should be {@code false}.
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory clbf)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = clbf;
		this.metadataReaderFactory = MetadataReaderFactory.create(clbf.getBeanClassLoader());
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		// Register externally managed config members on bean definition.
		findInjectionMetadata(beanName, beanType, beanDefinition);

		// Use opportunity to clear caches which are not needed after singleton instantiation.
		// The injectionMetadataCache itself is left intact since it cannot be reliably
		// reconstructed in terms of externally managed config members otherwise.
		if (beanDefinition.isSingleton()) {
			this.candidateConstructorsCache.remove(beanType);
			// With actual lookup overrides, keep it intact along with bean definition.
			if (!beanDefinition.hasMethodOverrides()) {
				this.lookupMethodsChecked.remove(beanName);
			}
		}
	}

	@Override
	public void resetBeanDefinition(String beanName) {
		this.lookupMethodsChecked.remove(beanName);
		this.injectionMetadataCache.remove(beanName);
	}

	@Override
	public @Nullable BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
		Class<?> beanClass = registeredBean.getBeanClass();
		String beanName = registeredBean.getBeanName();
		RootBeanDefinition beanDefinition = registeredBean.getMergedBeanDefinition();
		InjectionMetadata metadata = findInjectionMetadata(beanName, beanClass, beanDefinition);
		Collection<AutowiredElement> autowiredElements = getAutowiredElements(metadata,
				beanDefinition.getPropertyValues());
		if (!ObjectUtils.isEmpty(autowiredElements)) {
			return new AotContribution(beanClass, autowiredElements, getAutowireCandidateResolver());
		}
		return null;
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Collection<AutowiredElement> getAutowiredElements(InjectionMetadata metadata, PropertyValues propertyValues) {
		return (Collection) metadata.getInjectedElements(propertyValues);
	}

	private @Nullable AutowireCandidateResolver getAutowireCandidateResolver() {
		if (this.beanFactory instanceof DefaultListableBeanFactory lbf) {
			return lbf.getAutowireCandidateResolver();
		}
		return null;
	}

	private InjectionMetadata findInjectionMetadata(String beanName, Class<?> beanType, RootBeanDefinition beanDefinition) {
		// TODO 取得所有自动注入相关的元数据信息
		InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
		// TODO 将非外部管理的元素加入缓存
		metadata.checkConfigMembers(beanDefinition);
		return metadata;
	}

	@Override
	public Class<?> determineBeanType(Class<?> beanClass, String beanName) throws BeanCreationException {
		checkLookupMethods(beanClass, beanName);

		// Pick up subclass with fresh lookup method override from above
		if (this.beanFactory instanceof AbstractAutowireCapableBeanFactory aacBeanFactory) {
			RootBeanDefinition mbd = (RootBeanDefinition) this.beanFactory.getMergedBeanDefinition(beanName);
			if (mbd.getFactoryMethodName() == null && mbd.hasBeanClass()) {
				return aacBeanFactory.getInstantiationStrategy().getActualBeanClass(mbd, beanName, aacBeanFactory);
			}
		}
		return beanClass;
	}

	@Override
	public Constructor<?> @Nullable [] determineCandidateConstructors(Class<?> beanClass, final String beanName)
			throws BeanCreationException {

		checkLookupMethods(beanClass, beanName);

		// Quick check on the concurrent map first, with minimal locking.
		// TODO 首先看一下缓存里有没有要实例化的bean的候选构造器, 如果是第一次调用, 缓存里是没有的. 如果不是第一次调用, 直接返回缓存里的候选构造器
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		if (candidateConstructors == null) {
			// Fully synchronized resolution now...
			synchronized (this.candidateConstructorsCache) {
				// TODO 这里会有一个double check来防止其他线程在同步之前处理了查找候选构造器这个逻辑
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				if (candidateConstructors == null) {
					Constructor<?>[] rawCandidates;
					try {
						// TODO 拿到bean引用的Class对象的所有构造器
						rawCandidates = beanClass.getDeclaredConstructors();
					}
					catch (Throwable ex) {
						throw new BeanCreationException(beanName,
								"Resolution of declared constructors on bean Class [" + beanClass.getName() +
								"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
					}
					List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
					Constructor<?> requiredConstructor = null;
					Constructor<?> defaultConstructor = null;
					// TODO 找出首选的构造器, 非Kotlin返回的全是null
					Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
					int nonSyntheticConstructors = 0;
					for (Constructor<?> candidate : rawCandidates) {
						// TODO 遍历所有的候选构造器
						if (!candidate.isSynthetic()) {
							// TODO 如果这个构造器不是由Java编译器引入, 则记录非Java编译器引用构造器的数量自增1
							nonSyntheticConstructors++;
						}
						else if (primaryConstructor != null) {
							// TODO 对于由Java编译器引入的构造器来说, 如果有首选的构造器, 就跳过, 测试下一个构造器. 但这个值在非
							//  Kotlin情况下全都是null, 所以对于Java来说, 这是进不来的
							continue;
						}
						// TODO 把构造器上可以进行自动装配的注解拿出来(这里默认会拿出@Autowire, @Value, 或@Inject, 但其实只有@Autowire
						//  和@Inject, 因为@Value无法加到构造器上)
						MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);
						if (ann == null) {
							// TODO 没拿到的话, 看一下bean引用的Class对象是否被CGLIB增强过, 如果是, 则拿出原始Class对象
							Class<?> userClass = ClassUtils.getUserClass(beanClass);
							if (userClass != beanClass) {
								try {
									// TODO bean引用的Class对象和上面得到的Class对象不同时, 表示被CGLIB增强过. 需要到原始类中进行查找
									Constructor<?> superCtor =
											userClass.getDeclaredConstructor(candidate.getParameterTypes());
									ann = findAutowiredAnnotation(superCtor);
								}
								catch (NoSuchMethodException ex) {
									// Simply proceed, no equivalent superclass constructor found...
								}
							}
						}
						if (ann != null) {
							// TODO 如果在构造器上有@Autowire, 或@Inject注解, 开始进行自动装配
							if (requiredConstructor != null) {
								// TODO Spring可以进行构造器注入. 但对于同一个类, 只允许一个构造器的上的注解的'required'属性为true
								//  (@Autowire默认值为true. @Inject无法设置此属性值, 永远为true). 在检测到当前构造器时,
								//  如果已经有了'required = true'的构造器(requiredConstructor已经有值), 则会抛出
								//  BeanCreationException异常, 告诉你已经有了一个'required = true'的构造器, 并指出是哪个
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
										". Found constructor with 'required' Autowired annotation already: " +
										requiredConstructor);
							}
							// TODO 拿到注解上的'required'属性, @Autowire默认值为true. @Inject无法设置此属性值, 永远为true
							boolean required = determineRequiredStatus(ann);
							if (required) {
								// TODO 如果'required'属性是true的话, 当找不到合适的bean进行注入时会报错; 并且每个类只能有一个构造
								//  器的'required'属性为true, 否则也会报错
								if (!candidates.isEmpty()) {
									// TODO 如果当前构造器的'required = true', 并且还有其他构造器时, 即: candidates已经有值了,
									//  则会抛出BeanCreationException异常, 告诉你因为当前构造器是'required = true', 所以之前
									//  那此候选构造器都是无效的
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
											". Found constructor with 'required' Autowired annotation: " +
											candidate);
								}
								// TODO 将'required = true'的构造器做为必需要注入的构造器
								requiredConstructor = candidate;
							}
							// TODO 这里会把所有的满足条件的构造器都放到候选集合里, 并不只局限于'required = true'的构造器
							candidates.add(candidate);
						}
						else if (candidate.getParameterCount() == 0) {
							// TODO 对于没有注解, 且没参数的构造器, 会被做为默认构造器
							defaultConstructor = candidate;
						}
					}
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						if (requiredConstructor == null) {
							if (defaultConstructor != null) {
								// TODO 在没有明确的必需要注入的构造器时, 将默认构造器也加入到候选集合中
								candidates.add(defaultConstructor);
							}
							else if (candidates.size() == 1 && logger.isInfoEnabled()) {
								// TODO 如果只有一个非默认构造器时, 会通知这个唯一的构造器被标记为'required', 因为没有可以用来回
								//  退的默认构造器
								logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - " +
										"this constructor is effectively required since there is no " +
										"default constructor to fall back to: " + candidates.get(0));
							}
						}
						// TODO 转化成构造器数组
						candidateConstructors = candidates.toArray(EMPTY_CONSTRUCTOR_ARRAY);
					}
					else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
						// TODO 对于原生的唯一一个多参数构造器来说, 将其包装成Constructor数组
						candidateConstructors = new Constructor<?>[] {rawCandidates[0]};
					}
					else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
							defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
						// TODO 如果有2个不是由Java编译器引入的构造器, 且首选构造器与默认构造器不同时, 将这两个构造器包装成Constructor
						//  数组. 如果不用Kotlin, 这里是进不来的. 原因前面说了, primaryConstructor只有在Kotlin时才可能不为null
						candidateConstructors = new Constructor<?>[] {primaryConstructor, defaultConstructor};
					}
					else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
						// TODO 如果只有1构造器不是由Java编译器引入, 且首选构造器有值时, 将这两个构造器包装成Constructor数组.
						//  如果不用Kotlin, 这里是进不来的. 原因前面说了, primaryConstructor只有在Kotlin时才可能不为null
						candidateConstructors = new Constructor<?>[] {primaryConstructor};
					}
					else {
						candidateConstructors = EMPTY_CONSTRUCTOR_ARRAY;
					}
					// TODO 缓存构造器
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		return (candidateConstructors.length > 0 ? candidateConstructors : null);
	}

	private void checkLookupMethods(Class<?> beanClass, final String beanName) throws BeanCreationException {
		// TODO 处理@Lookup注解, 或<lookup-method />标签. 用于单例bean依赖原型bean时, 为单例bean提供动态实现.
		if (!this.lookupMethodsChecked.contains(beanName)) {
			// TODO 当lookup缓存中没有对应的bean时, 就要解析一下可能存在的@Lookup注解了(<lookup-method />标签是在XML解析时做的)
			if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
				// TODO 只处理可以使用@Lookup注解的Class引用
				try {
					Class<?> targetClass = beanClass;
					do {
						// TODO 执行bean引用的Class对象中的所有方法
						ReflectionUtils.doWithLocalMethods(targetClass, method -> {
							// TODO 取得当前操作方法的@Lookup注解
							Lookup lookup = method.getAnnotation(Lookup.class);
							if (lookup != null) {
								Assert.state(this.beanFactory != null, "No BeanFactory available");
								// TODO 为了实现动态依赖原型bean, 这里每次都要新建一个新的, 用于覆盖的LookupOverride对象
								LookupOverride override = new LookupOverride(method, lookup.value());
								try {
									RootBeanDefinition mbd = (RootBeanDefinition)
											this.beanFactory.getMergedBeanDefinition(beanName);
									// TODO 然后把这个用于覆盖的LookupOverride对象放到要实例化的bean的mbd的overrides缓存中, 这
									//  个在createBean()方法会用到
									mbd.getMethodOverrides().addOverride(override);
								}
								catch (NoSuchBeanDefinitionException ex) {
									throw new BeanCreationException(beanName,
											"Cannot apply @Lookup to beans without corresponding bean definition");
								}
							}
						});
						// TODO 一层一层向上解析, 直到遇到Object, 或再也没有父类时结束
						targetClass = targetClass.getSuperclass();
					}
					while (targetClass != null && targetClass != Object.class);

				}
				catch (IllegalStateException ex) {
					throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
				}
			}
			// TODO 然后把进行@Lookup检查过的bean放到缓存中
			this.lookupMethodsChecked.add(beanName);
		}
	}

	// TODO 把bean里所有标有@Autowire, @Value, @Inject注解的字段以及方法全都找出来, 生成包含有AutowiredFieldElement和
	//  AutowiredMethodElement的注入点元数据. 根据注入点元数据信息进行注入操作
	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		// TODO 把bean里所有标有@Autowire, @Value, @Inject注解的字段以及方法全都找出来, 生成包含有AutowiredFieldElement和
		//  AutowiredMethodElement的注入点元数据, 下面就可以根据注入点元数据进行注入操作
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
		try {
			metadata.inject(bean, beanName, pvs);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		return pvs;
	}

	/**
	 * <em>Native</em> processing method for direct calls with an arbitrary target
	 * instance, resolving all of its fields and methods which are annotated with
	 * one of the configured 'autowired' annotation types.
	 * @param bean the target instance to process
	 * @throws BeanCreationException if autowiring failed
	 * @see #setAutowiredAnnotationTypes(Set)
	 */
	public void processInjection(Object bean) throws BeanCreationException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					"Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}

	// TODO 把bean里所有标有@Autowire, @Value, @Inject注解的字段以及方法全都找出来, 生成包含AutowiredFieldElement和AutowiredMethodElement
	//  的注入点元数据后, 加入缓存中, 用于后续注入操作
	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		// Quick check on the concurrent map first, with minimal locking.
		// TODO 先从缓存拿注入点的元数据
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			// TODO 当没有对应的注入点的元数据或注入点的元数据发生变化时, 同步更新
			synchronized (this.injectionMetadataCache) {
				metadata = this.injectionMetadataCache.get(cacheKey);
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					// TODO double check还是需要更新元数据时, 先清空之前的元数据内容(如果存在的话)
					if (metadata != null) {
						metadata.clear(pvs);
					}
					// TODO 然后重新获取自动注入相关的元数据, 并放到缓存中
					metadata = buildAutowiringMetadata(clazz);
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
		// TODO 验证一下是否为注解的侯选类, 验证逻辑是:
		//  false: bean是"java."开头, 或者是Ordered类型
		//  true: 1. 注解类型是以"java."开头的
		//        2. 其他false情况
		if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
			// TODO bean是"java."开头, 或者是Ordered类型时, 不做注入处理, 返回empty
			return InjectionMetadata.EMPTY;
		}

		final List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		Class<?> targetClass = clazz;

		do {
			// TODO 开始遍历目标对象(创建bean时为bean的类型), 这里记录所有的注入元素信息
			final List<InjectionMetadata.InjectedElement> fieldElements = new ArrayList<>();
			// TODO 开始处理目标类(bean)的字段
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				// TODO 取得字段上的注解(默认的是@Autowire, @Value, @Inject)
				MergedAnnotation<?> ann = findAutowiredAnnotation(field);
				if (ann != null) {
					// TODO 字段包含@Autowire, @Value, @Inject注解时, 表示需要对其进行自动注入
					if (Modifier.isStatic(field.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static fields: " + field);
						}
						// TODO 静态字段无法注入, 直接返回
						return;
					}
					// TODO 取得字段上注解的required属性
					boolean required = determineRequiredStatus(ann);
					// TODO 用需要自动注入的字段, 及其注解的required属性一起包装成AutowiredFieldElement对象放入集合
					fieldElements.add(new AutowiredFieldElement(field, required));
				}
			});

			final List<InjectionMetadata.InjectedElement> methodElements = new ArrayList<>();
			// TODO 开始处理目标类(bean)的方法
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				// TODO 取得桥接方法(用于非泛型方法)
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					return;
				}
				// TODO 从桥接方法上取得注解(默认的是@Autowire, @Value, @Inject)
				MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					if (Modifier.isStatic(method.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static methods: " + method);
						}
						// TODO 静态方法无法注入, 直接返回
						return;
					}
					if (method.getParameterCount() == 0) {
						if (method.getDeclaringClass().isRecord()) {
							// Annotations on the compact constructor arguments made available on accessors, ignoring.
							return;
						}
						// TODO 要注入的方法没有参数时, 会报一个log
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation should only be used on methods with parameters: " +
									method);
						}
					}
					// TODO 取得方法上注解的required属性
					boolean required = determineRequiredStatus(ann);
					// TODO 取得桥接方法的属性
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					// TODO 用需要自动注入的方法, 注解上的required属性, 以及桥接方法的属性一起包装成AutowiredFieldElement对象放入集合
					methodElements.add(new AutowiredMethodElement(method, required, pd));
				}
			});

			elements.addAll(0, sortMethodElements(methodElements, targetClass));
			elements.addAll(0, fieldElements);
			// TODO 继续深入父类
			targetClass = targetClass.getSuperclass();
		}
		while (targetClass != null && targetClass != Object.class);
		// TODO 返回注入的元数据信息
		return InjectionMetadata.forElements(elements, clazz);
	}

	private @Nullable MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
		// TODO 取得目标的所有注解集合
		MergedAnnotations annotations = MergedAnnotations.from(ao);
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			MergedAnnotation<?> annotation = annotations.get(type);
			if (annotation.isPresent()) {
				// TODO 一但目标中包含@Autowire, @Value, @Inject时, 返回注解信息, 断路操作, 有一个满足就行
				return annotation;
			}
		}
		return null;
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 */
	// TODO 判断注解是否包含'required'属性. 默认会判断@Autowire, @Inject这两个注解. 对于@Autowire来说, 需要'required'
	//  属性明确设置为true. 对于@Inject注解, 则直接返回true
	protected boolean determineRequiredStatus(MergedAnnotation<?> ann) {
		return (ann.getValue(this.requiredParameterName).isEmpty() ||
				this.requiredParameterValue == ann.getBoolean(this.requiredParameterName));
	}

	/**
	 * Sort the method elements via ASM for deterministic declaration order if possible.
	 */
	private List<InjectionMetadata.InjectedElement> sortMethodElements(
			List<InjectionMetadata.InjectedElement> methodElements, Class<?> targetClass) {

		if (this.metadataReaderFactory != null && methodElements.size() > 1) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			try {
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(targetClass.getName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Autowired.class.getName());
				if (asmMethods.size() >= methodElements.size()) {
					List<InjectionMetadata.InjectedElement> candidateMethods = new ArrayList<>(methodElements);
					List<InjectionMetadata.InjectedElement> selectedMethods = new ArrayList<>(asmMethods.size());
					for (MethodMetadata asmMethod : asmMethods) {
						for (Iterator<InjectionMetadata.InjectedElement> it = candidateMethods.iterator(); it.hasNext();) {
							InjectionMetadata.InjectedElement element = it.next();
							if (element.getMember().getName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(element);
								it.remove();
								break;
							}
						}
					}
					if (selectedMethods.size() == methodElements.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						return selectedMethods;
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Autowired method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return methodElements;
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 */
	// TODO 注册自动装配的bean与当前bean的依赖关系
	private void registerDependentBeans(@Nullable String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			for (String autowiredBeanName : autowiredBeanNames) {
				// TODO 遍历所有自动装配过的bean
				if (this.beanFactory != null && this.beanFactory.containsBean(autowiredBeanName)) {
					// TODO 注册依赖的bean. 把当前操作的bean做为依赖自动装配的bean. 更新双向依赖关系
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	// TODO 解析缓存的内容. 如果是待注入项, 需要对其进行解析
	private @Nullable Object resolveCachedArgument(@Nullable String beanName, @Nullable Object cachedArgument) {
		if (cachedArgument instanceof DependencyDescriptor descriptor) {
			// TODO 如果缓存的是待注入项, 需要解决依赖关系
			Assert.state(this.beanFactory != null, "No BeanFactory available");
			return this.beanFactory.resolveDependency(descriptor, beanName, null, null);
		}
		else {
			// TODO 其他情况直接反回就好
			return cachedArgument;
		}
	}


	/**
	 * Base class representing injection information.
	 */
	private abstract static class AutowiredElement extends InjectionMetadata.InjectedElement {

		protected final boolean required;

		protected AutowiredElement(Member member, @Nullable PropertyDescriptor pd, boolean required) {
			super(member, pd);
			this.required = required;
		}
	}


	/**
	 * Class representing injection information about an annotated field.
	 */
	private class AutowiredFieldElement extends AutowiredElement {

		private volatile boolean cached;

		private volatile @Nullable Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null, required);
		}

		// TODO Field字段注入
		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			Field field = (Field) this.member;
			Object value;
			if (this.cached) {
				try {
					// TODO 有缓存的话, 直接解析缓存的值. 如果是DependencyDescriptor待注入项, 需要对其进行解析
					value = resolveCachedArgument(beanName, this.cachedFieldValue);
				}
				catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					this.cached = false;
					logger.debug("Failed to resolve cached argument", ex);
					value = resolveFieldValue(field, bean, beanName);
				}
			}
			else {
				// TODO 缓存里没有的话, 就开始重新查找值. 将方法参数(用于实例化bean的方法参数)封装为一个DependencyDescriptor.
				//  DependencyDescriptor是InjectionPoint的子类, 用于描述一个用于注入的依赖项的描述符, 比如: 字段(成员属性), 或
				//  方法(普通方法, 构造函数). 对于DependencyDescriptor描述的项来说, 可以对其进行自动装配, 即: 方法的参数也可以使用
				//  @Autowire, @Value这些注解来进行自动注入.
				value = resolveFieldValue(field, bean, beanName);
			}
			if (value != null) {
				ReflectionUtils.makeAccessible(field);
				// TODO 然后为字段重新设置值, 这时依赖注入完成(@Autowire, @Value等)
				field.set(bean, value);
			}
		}

		private @Nullable Object resolveFieldValue(Field field, Object bean, @Nullable String beanName) {
			DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
			desc.setContainingClass(bean.getClass());
			Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
			Assert.state(beanFactory != null, "No BeanFactory available");
			// TODO 取得容器中的类型解析器
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			Object value;
			try {
				value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
			}
			synchronized (this) {
				if (!this.cached) {
					if (value != null || this.required) {
						// TODO 如果解析出来了, 就设置到缓存里
						Object cachedFieldValue = desc;
						registerDependentBeans(beanName, autowiredBeanNames);
						if (value != null && autowiredBeanNames.size() == 1) {
							String autowiredBeanName = autowiredBeanNames.iterator().next();
							if (beanFactory.containsBean(autowiredBeanName) &&
									beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
								// TODO 如果只有一个为当前Field自动装配过的bean, 并且其已经注册到容器中了, 其类型还与Field类型
								//  相同, 将其包装为ShortcutDependencyDescriptor放到缓存里
								cachedFieldValue = new ShortcutDependencyDescriptor(desc, autowiredBeanName);
							}
						}
						this.cachedFieldValue = cachedFieldValue;
						// TODO 不管解没解析出来, 都做过操作了, 所以设置成缓存过了
						this.cached = true;
					}
					else {
						// TODO 没解析出来, 就是空的
						this.cachedFieldValue = null;
						// cached flag remains false
					}
				}
			}
			return value;
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 */
	private class AutowiredMethodElement extends AutowiredElement {

		private volatile boolean cached;

		private volatile Object @Nullable [] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, @Nullable PropertyDescriptor pd) {
			super(method, pd, required);
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			if (!shouldInject(pvs)) {
				return;
			}
			Method method = (Method) this.member;
			@Nullable Object[] arguments;
			if (this.cached) {
				try {
					// TODO 缓存过的话, 直接解析缓存的方法参数的值. 因为参数可能是多个, 所以这里其实是为每个参数调用一次resolvedCachedArgument()
					//  有一份而已. 具体处理与Field字段的一样. 如果是DependencyDescriptor待注入项, 需要对其进行解析
					arguments = resolveCachedArguments(beanName, this.cachedMethodArguments);
				}
				catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					this.cached = false;
					logger.debug("Failed to resolve cached argument", ex);
					arguments = resolveMethodArguments(method, bean, beanName);
				}
			}
			else {
				arguments = resolveMethodArguments(method, bean, beanName);
			}
			if (arguments != null) {
				try {
					ReflectionUtils.makeAccessible(method);
					method.invoke(bean, arguments);
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		private @Nullable Object @Nullable [] resolveCachedArguments(@Nullable String beanName, Object @Nullable [] cachedMethodArguments) {
			if (cachedMethodArguments == null) {
				return null;
			}
			@Nullable Object[] arguments = new Object[cachedMethodArguments.length];
			for (int i = 0; i < arguments.length; i++) {
				arguments[i] = resolveCachedArgument(beanName, cachedMethodArguments[i]);
			}
			return arguments;
		}

		private @Nullable Object @Nullable [] resolveMethodArguments(Method method, Object bean, @Nullable String beanName) {
			int argumentCount = method.getParameterCount();
			@Nullable Object[] arguments = new Object[argumentCount];
			DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
			Set<String> autowiredBeanNames = CollectionUtils.newLinkedHashSet(argumentCount);
			Assert.state(beanFactory != null, "No BeanFactory available");
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			for (int i = 0; i < arguments.length; i++) {
				MethodParameter methodParam = new MethodParameter(method, i);
				DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, this.required);
				currDesc.setContainingClass(bean.getClass());
				descriptors[i] = currDesc;
				try {
					Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeanNames, typeConverter);
					if (arg == null && !this.required && !methodParam.isOptional()) {
						arguments = null;
						break;
					}
					arguments[i] = arg;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
				}
			}
			synchronized (this) {
				if (!this.cached) {
					if (arguments != null) {
						DependencyDescriptor[] cachedMethodArguments = Arrays.copyOf(descriptors, argumentCount);
						registerDependentBeans(beanName, autowiredBeanNames);
						if (autowiredBeanNames.size() == argumentCount) {
							Iterator<String> it = autowiredBeanNames.iterator();
							Class<?>[] paramTypes = method.getParameterTypes();
							for (int i = 0; i < paramTypes.length; i++) {
								String autowiredBeanName = it.next();
								if (arguments[i] != null && beanFactory.containsBean(autowiredBeanName) &&
										beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
									cachedMethodArguments[i] = new ShortcutDependencyDescriptor(
											descriptors[i], autowiredBeanName);
								}
							}
						}
						this.cachedMethodArguments = cachedMethodArguments;
						this.cached = true;
					}
					else {
						this.cachedMethodArguments = null;
						// cached flag remains false
					}
				}
			}
			return arguments;
		}
	}


	/**
	 * DependencyDescriptor variant with a pre-resolved target bean name.
	 */
	@SuppressWarnings("serial")
	private static class ShortcutDependencyDescriptor extends DependencyDescriptor {

		private final String shortcut;

		public ShortcutDependencyDescriptor(DependencyDescriptor original, String shortcut) {
			super(original);
			this.shortcut = shortcut;
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			return beanFactory.getBean(this.shortcut, getDependencyType());
		}
	}


	/**
	 * {@link BeanRegistrationAotContribution} to autowire fields and methods.
	 */
	private static class AotContribution implements BeanRegistrationAotContribution {

		private static final String REGISTERED_BEAN_PARAMETER = "registeredBean";

		private static final String INSTANCE_PARAMETER = "instance";

		private final Class<?> target;

		private final Collection<AutowiredElement> autowiredElements;

		private final @Nullable AutowireCandidateResolver candidateResolver;

		AotContribution(Class<?> target, Collection<AutowiredElement> autowiredElements,
				@Nullable AutowireCandidateResolver candidateResolver) {

			this.target = target;
			this.autowiredElements = autowiredElements;
			this.candidateResolver = candidateResolver;
		}

		@Override
		public void applyTo(GenerationContext generationContext, BeanRegistrationCode beanRegistrationCode) {
			GeneratedClass generatedClass = generationContext.getGeneratedClasses()
					.addForFeatureComponent("Autowiring", this.target, type -> {
						type.addJavadoc("Autowiring for {@link $T}.", this.target);
						type.addModifiers(javax.lang.model.element.Modifier.PUBLIC);
					});
			GeneratedMethod generateMethod = generatedClass.getMethods().add("apply", method -> {
				method.addJavadoc("Apply the autowiring.");
				method.addModifiers(javax.lang.model.element.Modifier.PUBLIC,
						javax.lang.model.element.Modifier.STATIC);
				method.addParameter(RegisteredBean.class, REGISTERED_BEAN_PARAMETER);
				method.addParameter(this.target, INSTANCE_PARAMETER);
				method.returns(this.target);
				CodeWarnings codeWarnings = new CodeWarnings();
				codeWarnings.detectDeprecation(this.target);
				method.addCode(generateMethodCode(codeWarnings,
						generatedClass.getName(), generationContext.getRuntimeHints()));
				codeWarnings.suppress(method);
			});
			beanRegistrationCode.addInstancePostProcessor(generateMethod.toMethodReference());

			if (this.candidateResolver != null) {
				registerHints(generationContext.getRuntimeHints());
			}
		}

		private CodeBlock generateMethodCode(CodeWarnings codeWarnings,
				ClassName targetClassName, RuntimeHints hints) {

			CodeBlock.Builder code = CodeBlock.builder();
			for (AutowiredElement autowiredElement : this.autowiredElements) {
				code.addStatement(generateMethodStatementForElement(
						codeWarnings, targetClassName, autowiredElement, hints));
			}
			code.addStatement("return $L", INSTANCE_PARAMETER);
			return code.build();
		}

		private CodeBlock generateMethodStatementForElement(CodeWarnings codeWarnings,
				ClassName targetClassName, AutowiredElement autowiredElement, RuntimeHints hints) {

			Member member = autowiredElement.getMember();
			boolean required = autowiredElement.required;
			if (member instanceof Field field) {
				return generateMethodStatementForField(
						codeWarnings, targetClassName, field, required, hints);
			}
			if (member instanceof Method method) {
				return generateMethodStatementForMethod(
						codeWarnings, targetClassName, method, required, hints);
			}
			throw new IllegalStateException(
					"Unsupported member type " + member.getClass().getName());
		}

		private CodeBlock generateMethodStatementForField(CodeWarnings codeWarnings,
				ClassName targetClassName, Field field, boolean required, RuntimeHints hints) {

			hints.reflection().registerField(field);
			CodeBlock resolver = CodeBlock.of("$T.$L($S)",
					AutowiredFieldValueResolver.class,
					(!required ? "forField" : "forRequiredField"), field.getName());
			AccessControl accessControl = AccessControl.forMember(field);
			if (!accessControl.isAccessibleFrom(targetClassName)) {
				return CodeBlock.of("$L.resolveAndSet($L, $L)", resolver,
						REGISTERED_BEAN_PARAMETER, INSTANCE_PARAMETER);
			}
			else {
				codeWarnings.detectDeprecation(field);
				return CodeBlock.of("$L.$L = $L.resolve($L)", INSTANCE_PARAMETER,
						field.getName(), resolver, REGISTERED_BEAN_PARAMETER);
			}
		}

		private CodeBlock generateMethodStatementForMethod(CodeWarnings codeWarnings,
				ClassName targetClassName, Method method, boolean required, RuntimeHints hints) {

			CodeBlock.Builder code = CodeBlock.builder();
			code.add("$T.$L", AutowiredMethodArgumentsResolver.class,
					(!required ? "forMethod" : "forRequiredMethod"));
			code.add("($S", method.getName());
			if (method.getParameterCount() > 0) {
				codeWarnings.detectDeprecation(method.getParameterTypes());
				code.add(", $L", generateParameterTypesCode(method.getParameterTypes()));
			}
			code.add(")");
			AccessControl accessControl = AccessControl.forMember(method);
			if (!accessControl.isAccessibleFrom(targetClassName)) {
				hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
				code.add(".resolveAndInvoke($L, $L)", REGISTERED_BEAN_PARAMETER, INSTANCE_PARAMETER);
			}
			else {
				codeWarnings.detectDeprecation(method);
				hints.reflection().registerType(method.getDeclaringClass());
				CodeBlock arguments = new AutowiredArgumentsCodeGenerator(this.target,
						method).generateCode(method.getParameterTypes());
				CodeBlock injectionCode = CodeBlock.of("args -> $L.$L($L)",
						INSTANCE_PARAMETER, method.getName(), arguments);
				code.add(".resolve($L, $L)", REGISTERED_BEAN_PARAMETER, injectionCode);
			}
			return code.build();
		}

		private CodeBlock generateParameterTypesCode(Class<?>[] parameterTypes) {
			return CodeBlock.join(Arrays.stream(parameterTypes)
					.map(parameterType -> CodeBlock.of("$T.class", parameterType))
					.toList(), ", ");
		}

		private void registerHints(RuntimeHints runtimeHints) {
			this.autowiredElements.forEach(autowiredElement -> {
				boolean required = autowiredElement.required;
				Member member = autowiredElement.getMember();
				if (member instanceof Field field) {
					DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(field, required);
					registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
				}
				if (member instanceof Method method) {
					Class<?>[] parameterTypes = method.getParameterTypes();
					for (int i = 0; i < parameterTypes.length; i++) {
						MethodParameter methodParam = new MethodParameter(method, i);
						DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(methodParam, required);
						registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
					}
				}
			});
		}

		private void registerProxyIfNecessary(RuntimeHints runtimeHints, DependencyDescriptor dependencyDescriptor) {
			if (this.candidateResolver != null) {
				Class<?> proxyClass =
						this.candidateResolver.getLazyResolutionProxyClass(dependencyDescriptor, null);
				if (proxyClass != null) {
					ClassHintUtils.registerProxyIfNecessary(proxyClass, runtimeHints);
				}
			}
		}
	}

}
