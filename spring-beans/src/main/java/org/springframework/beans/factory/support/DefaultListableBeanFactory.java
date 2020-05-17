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

package org.springframework.beans.factory.support;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.inject.Provider;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.core.OrderComparator;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.log.LogMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CompositeIterator;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Spring's default implementation of the {@link ConfigurableListableBeanFactory}
 * and {@link BeanDefinitionRegistry} interfaces: a full-fledged bean factory
 * based on bean definition metadata, extensible through post-processors.
 *
 * <p>Typical usage is registering all bean definitions first (possibly read
 * from a bean definition file), before accessing beans. Bean lookup by name
 * is therefore an inexpensive operation in a local bean definition table,
 * operating on pre-resolved bean definition metadata objects.
 *
 * <p>Note that readers for specific bean definition formats are typically
 * implemented separately rather than as bean factory subclasses:
 * see for example {@link PropertiesBeanDefinitionReader} and
 * {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 *
 * <p>For an alternative implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} interface,
 * have a look at {@link StaticListableBeanFactory}, which manages existing
 * bean instances rather than creating new ones based on bean definitions.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 16 April 2001
 * @see #registerBeanDefinition
 * @see #addBeanPostProcessor
 * @see #getBean
 * @see #resolveDependency
 */
@SuppressWarnings("serial")
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

	@Nullable
	private static Class<?> javaxInjectProviderClass;

	static {
		try {
			javaxInjectProviderClass =
					ClassUtils.forName("javax.inject.Provider", DefaultListableBeanFactory.class.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - Provider interface simply not supported then.
			javaxInjectProviderClass = null;
		}
	}


	/** Map from serialized id to factory instance. */
	private static final Map<String, Reference<DefaultListableBeanFactory>> serializableFactories =
			new ConcurrentHashMap<>(8);

	/** Optional id for this factory, for serialization purposes. */
	@Nullable
	private String serializationId;

	/** Whether to allow re-registration of a different definition with the same name. */
	private boolean allowBeanDefinitionOverriding = true;

	/** Whether to allow eager class loading even for lazy-init beans. */
	private boolean allowEagerClassLoading = true;

	/** Optional OrderComparator for dependency Lists and arrays. */
	@Nullable
	private Comparator<Object> dependencyComparator;

	/** Resolver to use for checking if a bean definition is an autowire candidate. */
	// TODO 自动注入候选项解析器
	private AutowireCandidateResolver autowireCandidateResolver = new SimpleAutowireCandidateResolver();

	/** Map from dependency type to corresponding autowired value. */
	// TODO 依赖类型与自动注入值的缓存
	private final Map<Class<?>, Object> resolvableDependencies = new ConcurrentHashMap<>(16);

	/** Map of bean definition objects, keyed by bean name. */
	// TODO 注册中心, bean definition缓存
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

	/** Map from bean name to merged BeanDefinitionHolder. */
	private final Map<String, BeanDefinitionHolder> mergedBeanDefinitionHolders = new ConcurrentHashMap<>(256);

	/** Map of singleton and non-singleton bean names, keyed by dependency type. */
	private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<>(64);

	/** Map of singleton-only bean names, keyed by dependency type. */
	private final Map<Class<?>, String[]> singletonBeanNamesByType = new ConcurrentHashMap<>(64);

	/** List of bean definition names, in registration order. */
	private volatile List<String> beanDefinitionNames = new ArrayList<>(256);

	/** List of names of manually registered singletons, in registration order. */
	private volatile Set<String> manualSingletonNames = new LinkedHashSet<>(16);

	/** Cached array of bean definition names in case of frozen configuration. */
	@Nullable
	private volatile String[] frozenBeanDefinitionNames;

	/** Whether bean definition metadata may be cached for all beans. */
	private volatile boolean configurationFrozen = false;


	/**
	 * Create a new DefaultListableBeanFactory.
	 */
	public DefaultListableBeanFactory() {
		super();
	}

	/**
	 * Create a new DefaultListableBeanFactory with the given parent.
	 * @param parentBeanFactory the parent BeanFactory
	 */
	public DefaultListableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		super(parentBeanFactory);
	}


	/**
	 * Specify an id for serialization purposes, allowing this BeanFactory to be
	 * deserialized from this id back into the BeanFactory object, if needed.
	 */
	public void setSerializationId(@Nullable String serializationId) {
		if (serializationId != null) {
			serializableFactories.put(serializationId, new WeakReference<>(this));
		}
		else if (this.serializationId != null) {
			serializableFactories.remove(this.serializationId);
		}
		this.serializationId = serializationId;
	}

	/**
	 * Return an id for serialization purposes, if specified, allowing this BeanFactory
	 * to be deserialized from this id back into the BeanFactory object, if needed.
	 * @since 4.1.2
	 */
	@Nullable
	public String getSerializationId() {
		return this.serializationId;
	}

	/**
	 * Set whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * If not, an exception will be thrown. This also applies to overriding aliases.
	 * <p>Default is "true".
	 * @see #registerBeanDefinition
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Return whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * @since 4.1.2
	 */
	public boolean isAllowBeanDefinitionOverriding() {
		return this.allowBeanDefinitionOverriding;
	}

	/**
	 * Set whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>Default is "true". Turn this flag off to suppress class loading
	 * for lazy-init beans unless such a bean is explicitly requested.
	 * In particular, by-type lookups will then simply ignore bean definitions
	 * without resolved class name, instead of loading the bean classes on
	 * demand just to perform a type check.
	 * @see AbstractBeanDefinition#setLazyInit
	 */
	public void setAllowEagerClassLoading(boolean allowEagerClassLoading) {
		this.allowEagerClassLoading = allowEagerClassLoading;
	}

	/**
	 * Return whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * @since 4.1.2
	 */
	public boolean isAllowEagerClassLoading() {
		return this.allowEagerClassLoading;
	}

	/**
	 * Set a {@link java.util.Comparator} for dependency Lists and arrays.
	 * @since 4.0
	 * @see org.springframework.core.OrderComparator
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 */
	public void setDependencyComparator(@Nullable Comparator<Object> dependencyComparator) {
		this.dependencyComparator = dependencyComparator;
	}

	/**
	 * Return the dependency comparator for this BeanFactory (may be {@code null}.
	 * @since 4.0
	 */
	@Nullable
	public Comparator<Object> getDependencyComparator() {
		return this.dependencyComparator;
	}

	/**
	 * Set a custom autowire candidate resolver for this BeanFactory to use
	 * when deciding whether a bean definition should be considered as a
	 * candidate for autowiring.
	 */
	public void setAutowireCandidateResolver(final AutowireCandidateResolver autowireCandidateResolver) {
		Assert.notNull(autowireCandidateResolver, "AutowireCandidateResolver must not be null");
		if (autowireCandidateResolver instanceof BeanFactoryAware) {
			// TODO 支持容器感知时, 通过setBeanFactory()方法为Resolver设置容器环境的同时会创建用于操作Advisor的
			//  BeanFactoryAspectJAdvisorsBuilderAdapter, 主要提供AspectJ的支持
			if (System.getSecurityManager() != null) {
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(DefaultListableBeanFactory.this);
					return null;
				}, getAccessControlContext());
			}
			else {
				((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(this);
			}
		}
		this.autowireCandidateResolver = autowireCandidateResolver;
	}

	/**
	 * Return the autowire candidate resolver for this BeanFactory (never {@code null}).
	 */
	public AutowireCandidateResolver getAutowireCandidateResolver() {
		// TODO 默认是SimpleAutowireCandidateResolver
		return this.autowireCandidateResolver;
	}


	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof DefaultListableBeanFactory) {
			DefaultListableBeanFactory otherListableFactory = (DefaultListableBeanFactory) otherFactory;
			this.allowBeanDefinitionOverriding = otherListableFactory.allowBeanDefinitionOverriding;
			this.allowEagerClassLoading = otherListableFactory.allowEagerClassLoading;
			this.dependencyComparator = otherListableFactory.dependencyComparator;
			// A clone of the AutowireCandidateResolver since it is potentially BeanFactoryAware...
			setAutowireCandidateResolver(
					BeanUtils.instantiateClass(otherListableFactory.getAutowireCandidateResolver().getClass()));
			// Make resolvable dependencies (e.g. ResourceLoader) available here as well...
			this.resolvableDependencies.putAll(otherListableFactory.resolvableDependencies);
		}
	}


	//---------------------------------------------------------------------
	// Implementation of remaining BeanFactory methods
	//---------------------------------------------------------------------

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		return getBean(requiredType, (Object[]) null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getBean(Class<T> requiredType, @Nullable Object... args) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		Object resolved = resolveBean(ResolvableType.forRawClass(requiredType), args, false);
		if (resolved == null) {
			throw new NoSuchBeanDefinitionException(requiredType);
		}
		return (T) resolved;
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		return new BeanObjectProvider<T>() {
			@Override
			public T getObject() throws BeansException {
				T resolved = resolveBean(requiredType, null, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			public T getObject(Object... args) throws BeansException {
				T resolved = resolveBean(requiredType, args, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			@Nullable
			public T getIfAvailable() throws BeansException {
				try {
					return resolveBean(requiredType, null, false);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifAvailable(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfAvailable();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@Override
			@Nullable
			public T getIfUnique() throws BeansException {
				try {
					return resolveBean(requiredType, null, true);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifUnique(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfUnique();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@Override
			public Stream<T> stream() {
				return Arrays.stream(getBeanNamesForTypedStream(requiredType))
						.map(name -> (T) getBean(name))
						.filter(bean -> !(bean instanceof NullBean));
			}
			@Override
			public Stream<T> orderedStream() {
				String[] beanNames = getBeanNamesForTypedStream(requiredType);
				if (beanNames.length == 0) {
					return Stream.empty();
				}
				Map<String, T> matchingBeans = new LinkedHashMap<>(beanNames.length);
				for (String beanName : beanNames) {
					Object beanInstance = getBean(beanName);
					if (!(beanInstance instanceof NullBean)) {
						matchingBeans.put(beanName, (T) beanInstance);
					}
				}
				Stream<T> stream = matchingBeans.values().stream();
				return stream.sorted(adaptOrderComparator(matchingBeans));
			}
		};
	}

	@Nullable
	private <T> T resolveBean(ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) {
		NamedBeanHolder<T> namedBean = resolveNamedBean(requiredType, args, nonUniqueAsNull);
		if (namedBean != null) {
			return namedBean.getBeanInstance();
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			return ((DefaultListableBeanFactory) parent).resolveBean(requiredType, args, nonUniqueAsNull);
		}
		else if (parent != null) {
			ObjectProvider<T> parentProvider = parent.getBeanProvider(requiredType);
			if (args != null) {
				return parentProvider.getObject(args);
			}
			else {
				return (nonUniqueAsNull ? parentProvider.getIfUnique() : parentProvider.getIfAvailable());
			}
		}
		return null;
	}

	private String[] getBeanNamesForTypedStream(ResolvableType requiredType) {
		return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(this, requiredType);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		String[] frozenNames = this.frozenBeanDefinitionNames;
		if (frozenNames != null) {
			return frozenNames.clone();
		}
		else {
			return StringUtils.toStringArray(this.beanDefinitionNames);
		}
	}

	/**
	 * 取得满足类型要求的bean集合, 默认包含非单例bean, 以及允许急加载
	 *
	 * @param type the generically typed class or interface to match 要取得的类型
	 *
	 * @return 满足类型要求的bean的集合
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		return getBeanNamesForType(type, true, true);
	}

	/**
	 * 取得满足类型要求的bean集合, 提供是否包含非单例bean, 以及是否允许急加载的选项
	 *
	 * @param type the generically typed class or interface to match 要取得的类型
	 * @param includeNonSingletons whether to include prototype or scoped beans too
	 * or just singletons (also applies to FactoryBeans) 是否包含非单例bean
	 * @param allowEagerInit whether to initialize <i>lazy-init singletons</i> and
	 * <i>objects created by FactoryBeans</i> (or by factory methods with a
	 * "factory-bean" reference) for the type check. Note that FactoryBeans need to be
	 * eagerly initialized to determine their type: So be aware that passing in "true"
	 * for this flag will initialize FactoryBeans and "factory-bean" references. 是否支持急加载, 但这个参数这里没用过
	 *
	 * @return 满足类型要求的bean的集合
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		Class<?> resolved = type.resolve();
		if (resolved != null && !type.hasGenerics()) {
			// TODO 要取得的不是泛型时, 通过getBeanNamesForType()方法取
			return getBeanNamesForType(resolved, includeNonSingletons, allowEagerInit);
		}
		else {
			// TODO 其他情况直接调用doGetBeanNamesForType()方法取
			return doGetBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		}
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		return getBeanNamesForType(type, true, true);
	}

	@Override
	// TODO 根据指定type类型, 在容器中取得对应type类型的bean
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
			// TODO 以下情况发生时, 会根据包装为ResolvableType的type类型来取得bean名字的集合:
			//  1. 不缓存bean definition的元数据信息;
			//  2. 或者没有指定type;
			//  3. 或者不支持急加载;
			return doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit);
		}
		// TODO 根据是否包含非单例实例来决定bean的范围:
		//  true: allBeanNamesByType缓存中取得所有type类型相同的bean;
		//  false: singletonBeanNamesByType缓存中取得所有type类型相同的单例bean
		Map<Class<?>, String[]> cache =
				(includeNonSingletons ? this.allBeanNamesByType : this.singletonBeanNamesByType);
		// TODO 然后拿出type类型所对应的bean的名字的数组, 如果有就直接返回
		String[] resolvedBeanNames = cache.get(type);
		if (resolvedBeanNames != null) {
			return resolvedBeanNames;
		}
		// TODO 取得指定type类型的bean名字的集合, 这里允许急加载来进行工厂类的初始化
		resolvedBeanNames = doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, true);
		if (ClassUtils.isCacheSafe(type, getBeanClassLoader())) {
			// TODO 如果type类型是缓存安全的类型, 将其与获得的bean名字集合放到缓存中
			cache.put(type, resolvedBeanNames);
		}
		// TODO 返回解析后的, 包括指定type类型的bean的名字的数组
		return resolvedBeanNames;
	}

	// TODO 取得指定type类型的bean
	private String[] doGetBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		List<String> result = new ArrayList<>();

		// Check all bean definitions.
		// TODO 首先从注册中心中找相同类型的bean加到结果集
		for (String beanName : this.beanDefinitionNames) {
			// Only consider bean as eligible if the bean name
			// is not defined as alias for some other bean.
			// TODO 遍历注册中心中所有的beanDefinition, 这里不关注别名
			if (!isAlias(beanName)) {
				try {
					// TODO 取得当前操作的bean的mbd
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// Only check bean definition if it is complete.
					if (!mbd.isAbstract() && (allowEagerInit ||
							(mbd.hasBeanClass() || !mbd.isLazyInit() || isAllowEagerClassLoading()) &&
									!requiresEagerInitForType(mbd.getFactoryBeanName()))) {
						// TODO 只处理bean definition已经完成的情况:
						//  1. 当前mbd不是抽象类
						//  2. 并且当前操作允许急加载
						//     2.1 或者指定了class属性, 或当前mbd不是懒加载, 或者工厂允许急加载
						//     2.2 并且工厂类不需要急加载来确定类型
						// TODO 判断一下当前mbd是否为工厂类
						boolean isFactoryBean = isFactoryBean(beanName, mbd);
						// TODO 取得当前mbd的代理的目标
						BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
						boolean matchFound = false;
						boolean allowFactoryBeanInit = allowEagerInit || containsSingleton(beanName);
						boolean isNonLazyDecorated = dbd != null && !mbd.isLazyInit();
						if (!isFactoryBean) {
							// TODO 当前mbd不是工厂类时
							if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
								// TODO 满足以下情况时, 判断bean是否与要取得的类型相匹配:
								//  1. 允许包含非单例bean;
								//  2. 或者bean是个单例时.
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						else  {
							// TODO 当前操作的bean是工厂类时, 除了验证bean实例外, 还会考虑bean本身是工厂类的情况
							if (includeNonSingletons || isNonLazyDecorated ||
									(allowFactoryBeanInit && isSingleton(beanName, mbd, dbd))) {
								// TODO 满足以下情况时, 判断bean是否与要取得的类型相匹配:
								//  1. 允许包含非单例bean;
								//  2. 或者mbd是个代理类, 且没设置懒加载;
								//  3. 或者允许工厂类初始化且当前mbd是单例时.
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
							if (!matchFound) {
								// In case of FactoryBean, try to match FactoryBean instance itself next.
								// TODO 不匹配时, 再试一下工厂类实例自身(加上'&'前缀)是否匹配
								beanName = FACTORY_BEAN_PREFIX + beanName;
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						if (matchFound) {
							// TODO 将匹配到的bean的名字加入到结果集中
							result.add(beanName);
						}
					}
				}
				catch (CannotLoadBeanClassException | BeanDefinitionStoreException ex) {
					if (allowEagerInit) {
						throw ex;
					}
					// Probably a placeholder: let's ignore it for type matching purposes.
					LogMessage message = (ex instanceof CannotLoadBeanClassException) ?
							LogMessage.format("Ignoring bean class loading failure for bean '%s'", beanName) :
							LogMessage.format("Ignoring unresolvable metadata in bean definition '%s'", beanName);
					logger.trace(message, ex);
					onSuppressedException(ex);
				}
			}
		}


		// Check manually registered singletons too.
		// TODO 然后从手动注册的单例缓存中查找对应type的bean放到结果集中
		for (String beanName : this.manualSingletonNames) {
			try {
				// In case of FactoryBean, match object created by FactoryBean.
				if (isFactoryBean(beanName)) {
					// TODO 当前手动注册的单例bean是工厂类时, 看由工厂类所创建的对象是否匹配指定的type
					if ((includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type)) {
						// TODO 满足以下条件的bean会被加入到结果集中(不包含工厂类本身)
						//      1. 允许包含非单例, 或手动注册的当前单例bean是单例
						//      2. 手动注册的当前单例bean的type与要取得的type相同
						result.add(beanName);
						// Match found for this bean: do not match FactoryBean itself anymore.
						continue;
					}
					// In case of FactoryBean, try to match FactoryBean itself next.
					// TODO 没找到时, 尝试一下工厂类本身('&'+beanName)
					beanName = FACTORY_BEAN_PREFIX + beanName;
				}
				// Match raw bean instance (might be raw FactoryBean).
				if (isTypeMatch(beanName, type)) {
					// TODO 如果当前手动注册的单例bean的type与指定的type相同时, 将其加入到结果集中
					result.add(beanName);
				}
			}
			catch (NoSuchBeanDefinitionException ex) {
				// Shouldn't happen - probably a result of circular reference resolution...
				logger.trace(LogMessage.format("Failed to check manually registered singleton with name '%s'", beanName), ex);
			}
		}
		// TODO 返回注册中心以及手动加载的单例bean的名字的集合
		return StringUtils.toStringArray(result);
	}

	// TODO 判断bean是否为单例, 如果是个代理类(mbd的代理目标dbd存在时), scope与mbd相同, 判断mbd是否为单例即可(mbd.isSingleton)
	//  如果不是个代理类, 就用根据bean名来判断是否为单例
	private boolean isSingleton(String beanName, RootBeanDefinition mbd, @Nullable BeanDefinitionHolder dbd) {
		return (dbd != null ? mbd.isSingleton() : isSingleton(beanName));
	}

	/**
	 * Check whether the specified bean would need to be eagerly initialized
	 * in order to determine its type.
	 * @param factoryBeanName a factory-bean reference that the bean definition
	 * defines a factory method for
	 * @return whether eager initialization is necessary
	 */
	// TODO 判断工厂类是否需要急加载来确定类型。
	private boolean requiresEagerInitForType(@Nullable String factoryBeanName) {
		return (factoryBeanName != null && isFactoryBean(factoryBeanName) && !containsSingleton(factoryBeanName));
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		return getBeansOfType(type, true, true);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		String[] beanNames = getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		Map<String, T> result = new LinkedHashMap<>(beanNames.length);
		for (String beanName : beanNames) {
			try {
				Object beanInstance = getBean(beanName);
				if (!(beanInstance instanceof NullBean)) {
					result.put(beanName, (T) beanInstance);
				}
			}
			catch (BeanCreationException ex) {
				Throwable rootCause = ex.getMostSpecificCause();
				if (rootCause instanceof BeanCurrentlyInCreationException) {
					BeanCreationException bce = (BeanCreationException) rootCause;
					String exBeanName = bce.getBeanName();
					if (exBeanName != null && isCurrentlyInCreation(exBeanName)) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring match to currently created bean '" + exBeanName + "': " +
									ex.getMessage());
						}
						onSuppressedException(ex);
						// Ignore: indicates a circular reference when autowiring constructors.
						// We want to find matches other than the currently created bean itself.
						continue;
					}
				}
				throw ex;
			}
		}
		return result;
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		List<String> result = new ArrayList<>();
		for (String beanName : this.beanDefinitionNames) {
			BeanDefinition beanDefinition = getBeanDefinition(beanName);
			if (!beanDefinition.isAbstract() && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		for (String beanName : this.manualSingletonNames) {
			if (!result.contains(beanName) && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		return StringUtils.toStringArray(result);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
		String[] beanNames = getBeanNamesForAnnotation(annotationType);
		Map<String, Object> result = new LinkedHashMap<>(beanNames.length);
		for (String beanName : beanNames) {
			Object beanInstance = getBean(beanName);
			if (!(beanInstance instanceof NullBean)) {
				result.put(beanName, beanInstance);
			}
		}
		return result;
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		return findMergedAnnotationOnBean(beanName, annotationType)
				.synthesize(MergedAnnotation::isPresent).orElse(null);
	}

	private <A extends Annotation> MergedAnnotation<A> findMergedAnnotationOnBean(
			String beanName, Class<A> annotationType) {

		Class<?> beanType = getType(beanName);
		if (beanType != null) {
			MergedAnnotation<A> annotation =
					MergedAnnotations.from(beanType, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		if (containsBeanDefinition(beanName)) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// Check raw bean class, e.g. in case of a proxy.
			if (bd.hasBeanClass()) {
				Class<?> beanClass = bd.getBeanClass();
				if (beanClass != beanType) {
					MergedAnnotation<A> annotation =
							MergedAnnotations.from(beanClass, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
					if (annotation.isPresent()) {
						return annotation;
					}
				}
			}
			// Check annotations declared on factory method, if any.
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				MergedAnnotation<A> annotation =
						MergedAnnotations.from(factoryMethod, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
				if (annotation.isPresent()) {
					return annotation;
				}
			}
		}
		return MergedAnnotation.missing();
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void registerResolvableDependency(Class<?> dependencyType, @Nullable Object autowiredValue) {
		Assert.notNull(dependencyType, "Dependency type must not be null");
		if (autowiredValue != null) {
			if (!(autowiredValue instanceof ObjectFactory || dependencyType.isInstance(autowiredValue))) {
				throw new IllegalArgumentException("Value [" + autowiredValue +
						"] does not implement specified dependency type [" + dependencyType.getName() + "]");
			}
			this.resolvableDependencies.put(dependencyType, autowiredValue);
		}
	}

	/**
	 *
	 * @param beanName the name of the bean to check 自动装配候选项
	 * @param descriptor the descriptor of the dependency to resolve 字段, 方法, 或构造函数所表示的依赖描述的待注入项, 对于多值
	 *                   类型, 这里会是被包装过的包含多元素的MultiElementDescriptor
	 * @return
	 * @throws NoSuchBeanDefinitionException
	 */
	@Override
	// TODO 判断给定的bean是否为注入项的自动装配候选(可以自动装配到注入项中)
	public boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {
		// TODO 根据getAutowireCandidateResolver()得到的解析器(默认是SimpleAutowireCandidateResolver)进行check, 判断指定的bean
		//  是否为DependencyDescriptor所描述的注入项的自动装配候选
		return isAutowireCandidate(beanName, descriptor, getAutowireCandidateResolver());
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 *
	 * @param beanName the name of the bean definition to check 自动装配候选项
	 * @param descriptor the descriptor of the dependency to resolve 字段, 方法, 或构造函数所表示的依赖描述的待注入项, 对于多值
	 *                   类型, 这里会是被包装过的包含多元素的MultiElementDescriptor
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm 自动注入候选解析器, 默认为
	 *                    SimpleAutowireCandidateResolver
	 * @return whether the bean should be considered as autowire candidate
	 */
	// TODO 用AutowireCandidateResolver所指定的解析器来判断给定的bean是否为注入项的自动装配候选(可以自动装配到注入项中)
	protected boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor, AutowireCandidateResolver resolver)
			throws NoSuchBeanDefinitionException {
		// TODO 取得自动装配候选项的名字(去掉其中的'&')
		String beanDefinitionName = BeanFactoryUtils.transformedBeanName(beanName);
		if (containsBeanDefinition(beanDefinitionName)) {
			// TODO 注册中心存在自动装配候选项时, 为其生成mbd, 然后用指定的解析器验证其是否可以进行自动装配
			return isAutowireCandidate(beanName, getMergedLocalBeanDefinition(beanDefinitionName), descriptor, resolver);
		}
		else if (containsSingleton(beanName)) {
			// TODO 对于直接registerSingleton的情况, 注册中心不会有其信息, 会直接进入单例缓存中. 这时用自动装配项的type类型
			//  生成一个RootBeanDefinition, 然后用指定的解析器验证其是否可以进行自动装配
			return isAutowireCandidate(beanName, new RootBeanDefinition(getType(beanName)), descriptor, resolver);
		}
		// TODO 当前容器没有注入项时, 向上到父容器进行查找. 父容器有可能为null, 为null时走else默认值了true, 表示可以注入
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			// TODO 父容器是DefaultListableBeanFactory类型时, 代理给父容器去验证, 解析器依然使用当前的解析器
			return ((DefaultListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor, resolver);
		}
		else if (parent instanceof ConfigurableListableBeanFactory) {
			// If no DefaultListableBeanFactory, can't pass the resolver along.
			// TODO 父容器是ConfigurableListableBeanFactory类型时, 直接代理给父容器去验证(不使用当前解析器, 由父类去找自己的解析器)
			return ((ConfigurableListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor);
		}
		else {
			// TODO 其他情况都表示找到了
			return true;
		}
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 *
	 * @param beanName the name of the bean definition to check 自动装配候选项
	 * @param mbd the merged bean definition to check 待验证的自动装配候选项的mbd:
	 *            1. 对于待验证的自动装配候选项存在于注册中心的情况, 这里是自动装配候选项所对应的mbd
	 *            2. 对于用registerSingleton直接注册为单例的bean来说, 这里会是单例bean的type类型所表示的mbd
	 * @param descriptor the descriptor of the dependency to resolve 字段, 方法, 或构造函数所表示的依赖描述的待注入项, 对于多值
	 *                   类型, 这里会是被包装过的包含多元素的MultiElementDescriptor
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	// TODO 用AutowireCandidateResolver所指定的解析器来判断给定的bean是否为注入项的自动装配候选(可以自动装配到注入项中)
	protected boolean isAutowireCandidate(String beanName, RootBeanDefinition mbd,
			DependencyDescriptor descriptor, AutowireCandidateResolver resolver) {
		// TODO 取得自动装配候选项的名字(去掉其中的'&')
		String beanDefinitionName = BeanFactoryUtils.transformedBeanName(beanName);
		// TODO 解析mbd的class
		resolveBeanClass(mbd, beanDefinitionName);
		if (mbd.isFactoryMethodUnique && mbd.factoryMethodToIntrospect == null) {
			// TODO 自动装配候选项没有被@Bean标注的同名方法(唯一的方法), 且没有缓存的内省工厂方法时, 用当前容器创建一个构造解析器
			//  ConstructorResolver来解析注入项生成的mbd的工厂方法, 解析后会填充factoryMethodToIntrospect缓存
			new ConstructorResolver(this).resolveFactoryMethodIfPossible(mbd);
		}
		BeanDefinitionHolder holder = (beanName.equals(beanDefinitionName) ?
				this.mergedBeanDefinitionHolders.computeIfAbsent(beanName,
						key -> new BeanDefinitionHolder(mbd, beanName, getAliases(beanDefinitionName))) :
				new BeanDefinitionHolder(mbd, beanName, getAliases(beanDefinitionName)));
		// TODO 将待验证的自动装配候选项包装为一个bd, 然后将其与注入项一起交给解析器去进行判断. 以下解析器实现了isAutowireCandidate()方法:
		//  1. AutowireCandidateResolver接口: 默认方法, 返回的是mbd的autowireCandidate属性对应的值
		//  2. SimpleAutowireCandidateResolver类: 实现了AutowireCandidateResolver接口, 同样返回的是mbd的autowireCandidate
		//                                        属性对应的值, 这个是以前的方法, Java 8后实际上可以去掉了
		//  3. GenericTypeAwareAutowireCandidateResolver类: 扩展了SimpleAutowireCandidateResolver, 增加了对泛型类型的匹配检测
		//  4. QualifierAnnotationAutowireCandidateResolver类: 扩展了GenericTypeAwareAutowireCandidateResolver, 增加了由
		//                                                     @Qualifier注解指定的约束的情况
		return resolver.isAutowireCandidate(holder, descriptor);
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}
		return bd;
	}

	@Override
	public Iterator<String> getBeanNamesIterator() {
		CompositeIterator<String> iterator = new CompositeIterator<>();
		iterator.add(this.beanDefinitionNames.iterator());
		iterator.add(this.manualSingletonNames.iterator());
		return iterator;
	}

	@Override
	protected void clearMergedBeanDefinition(String beanName) {
		super.clearMergedBeanDefinition(beanName);
		this.mergedBeanDefinitionHolders.remove(beanName);
	}

	@Override
	public void clearMetadataCache() {
		super.clearMetadataCache();
		this.mergedBeanDefinitionHolders.clear();
		clearByTypeCache();
	}

	@Override
	public void freezeConfiguration() {
		this.configurationFrozen = true;
		this.frozenBeanDefinitionNames = StringUtils.toStringArray(this.beanDefinitionNames);
	}

	@Override
	public boolean isConfigurationFrozen() {
		return this.configurationFrozen;
	}

	/**
	 * Considers all beans as eligible for metadata caching
	 * if the factory's configuration has been marked as frozen.
	 * @see #freezeConfiguration()
	 */
	@Override
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return (this.configurationFrozen || super.isBeanEligibleForMetadataCaching(beanName));
	}

	@Override
	public void preInstantiateSingletons() throws BeansException {
		if (logger.isTraceEnabled()) {
			logger.trace("Pre-instantiating singletons in " + this);
		}

		// Iterate over a copy to allow for init methods which in turn register new bean definitions.
		// While this may not be part of the regular factory bootstrap, it does otherwise work fine.
		List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

		// Trigger initialization of all non-lazy singleton beans...
		for (String beanName : beanNames) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			if (!bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
				// TODO 判断当前处理的bean是否是工厂类
				if (isFactoryBean(beanName)) {
					Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
					if (bean instanceof FactoryBean) {
						final FactoryBean<?> factory = (FactoryBean<?>) bean;
						boolean isEagerInit;
						if (System.getSecurityManager() != null && factory instanceof SmartFactoryBean) {
							isEagerInit = AccessController.doPrivileged((PrivilegedAction<Boolean>)
											((SmartFactoryBean<?>) factory)::isEagerInit,
									getAccessControlContext());
						}
						else {
							isEagerInit = (factory instanceof SmartFactoryBean &&
									((SmartFactoryBean<?>) factory).isEagerInit());
						}
						if (isEagerInit) {
							getBean(beanName);
						}
					}
				}
				else {
					getBean(beanName);
				}
			}
		}

		// Trigger post-initialization callback for all applicable beans...
		for (String beanName : beanNames) {
			Object singletonInstance = getSingleton(beanName);
			if (singletonInstance instanceof SmartInitializingSingleton) {
				final SmartInitializingSingleton smartSingleton = (SmartInitializingSingleton) singletonInstance;
				if (System.getSecurityManager() != null) {
					AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
						smartSingleton.afterSingletonsInstantiated();
						return null;
					}, getAccessControlContext());
				}
				else {
					smartSingleton.afterSingletonsInstantiated();
				}
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanDefinitionRegistry interface
	//---------------------------------------------------------------------

	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {

		Assert.hasText(beanName, "Bean name must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");

		if (beanDefinition instanceof AbstractBeanDefinition) {
			try {
				// TODO 注册前验证AbstractBeanDefinition类型的bd的正确性, 有两种可能会抛出异常:
				//  1. methodOverrides与工厂方法同时存在
				//  2. beanDefinition中没有覆盖的方法
				((AbstractBeanDefinition) beanDefinition).validate();
			}
			catch (BeanDefinitionValidationException ex) {
				throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
						"Validation of bean definition failed", ex);
			}
		}

		BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
		// TODO 处理重名的bean
		if (existingDefinition != null) {
			if (!isAllowBeanDefinitionOverriding()) {
				// TODO 不允许覆盖时抛出异常
				throw new BeanDefinitionOverrideException(beanName, beanDefinition, existingDefinition);
			}
			else if (existingDefinition.getRole() < beanDefinition.getRole()) {
				// e.g. was ROLE_APPLICATION, now overriding with ROLE_SUPPORT or ROLE_INFRASTRUCTURE
				if (logger.isInfoEnabled()) {
					logger.info("Overriding user-defined bean definition for bean '" + beanName +
							"' with a framework-generated bean definition: replacing [" +
							existingDefinition + "] with [" + beanDefinition + "]");
				}
			}
			else if (!beanDefinition.equals(existingDefinition)) {
				if (logger.isDebugEnabled()) {
					logger.debug("Overriding bean definition for bean '" + beanName +
							"' with a different definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Overriding bean definition for bean '" + beanName +
							"' with an equivalent definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			// TODO 用新的beanDefinition覆盖已存在的beanDefinition
			this.beanDefinitionMap.put(beanName, beanDefinition);
		}
		else {
			if (hasBeanCreationStarted()) {
				// Cannot modify startup-time collection elements anymore (for stable iteration)
				// TODO 如果开始了bean初始化了动作, 即alreadyCreate已经有元素时, 需要锁map来安全的进行注册
				synchronized (this.beanDefinitionMap) {
					// TODO 先放到缓存中
					this.beanDefinitionMap.put(beanName, beanDefinition);
					List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames.size() + 1);
					updatedDefinitions.addAll(this.beanDefinitionNames);
					updatedDefinitions.add(beanName);
					// TODO 注册后更新beanDefinitionNames
					this.beanDefinitionNames = updatedDefinitions;
					// TODO 删除手动注册的singleton实例, 即通过registerSingleton(String beanName, Object singletonObject)注册的
					removeManualSingletonName(beanName);
				}
			}
			else {
				// Still in startup registration phase
				// TODO 没进行过初始化时, 直接注册即可, 不需要同步
				this.beanDefinitionMap.put(beanName, beanDefinition);
				this.beanDefinitionNames.add(beanName);
				removeManualSingletonName(beanName);
			}
			this.frozenBeanDefinitionNames = null;
		}

		if (existingDefinition != null || containsSingleton(beanName)) {
			// TODO 如果存在的同名bean, 更新后需要进行重置
			//  1. 清理缓存
			//  2. 注销单例实例
			//  3. 重置后处理器中所有的bean
			//  4. 递归重置beanDefinitionMap中的内置bean
			resetBeanDefinition(beanName);
		}
		else if (isConfigurationFrozen()) {
			clearByTypeCache();
		}
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		Assert.hasText(beanName, "'beanName' must not be empty");

		BeanDefinition bd = this.beanDefinitionMap.remove(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}

		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames);
				updatedDefinitions.remove(beanName);
				this.beanDefinitionNames = updatedDefinitions;
			}
		}
		else {
			// Still in startup registration phase
			this.beanDefinitionNames.remove(beanName);
		}
		this.frozenBeanDefinitionNames = null;

		resetBeanDefinition(beanName);
	}

	/**
	 * Reset all bean definition caches for the given bean,
	 * including the caches of beans that are derived from it.
	 * <p>Called after an existing bean definition has been replaced or removed,
	 * triggering {@link #clearMergedBeanDefinition}, {@link #destroySingleton}
	 * and {@link MergedBeanDefinitionPostProcessor#resetBeanDefinition} on the
	 * given bean and on all bean definitions that have the given bean as parent.
	 * @param beanName the name of the bean to reset
	 * @see #registerBeanDefinition
	 * @see #removeBeanDefinition
	 */
	protected void resetBeanDefinition(String beanName) {
		// Remove the merged bean definition for the given bean, if already created.
		clearMergedBeanDefinition(beanName);

		// Remove corresponding bean from singleton cache, if any. Shouldn't usually
		// be necessary, rather just meant for overriding a context's default beans
		// (e.g. the default StaticMessageSource in a StaticApplicationContext).
		destroySingleton(beanName);

		// Notify all post-processors that the specified bean definition has been reset.
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.resetBeanDefinition(beanName);
		}

		// Reset all bean definitions that have the given bean as parent (recursively).
		for (String bdName : this.beanDefinitionNames) {
			if (!beanName.equals(bdName)) {
				BeanDefinition bd = this.beanDefinitionMap.get(bdName);
				// Ensure bd is non-null due to potential concurrent modification
				// of the beanDefinitionMap.
				if (bd != null && beanName.equals(bd.getParentName())) {
					resetBeanDefinition(bdName);
				}
			}
		}
	}

	/**
	 * Only allows alias overriding if bean definition overriding is allowed.
	 */
	@Override
	protected boolean allowAliasOverriding() {
		return isAllowBeanDefinitionOverriding();
	}

	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		super.registerSingleton(beanName, singletonObject);
		updateManualSingletonNames(set -> set.add(beanName), set -> !this.beanDefinitionMap.containsKey(beanName));
		clearByTypeCache();
	}

	@Override
	public void destroySingletons() {
		super.destroySingletons();
		updateManualSingletonNames(Set::clear, set -> !set.isEmpty());
		clearByTypeCache();
	}

	@Override
	public void destroySingleton(String beanName) {
		super.destroySingleton(beanName);
		removeManualSingletonName(beanName);
		clearByTypeCache();
	}

	private void removeManualSingletonName(String beanName) {
		updateManualSingletonNames(set -> set.remove(beanName), set -> set.contains(beanName));
	}

	/**
	 * Update the factory's internal set of manual singleton names.
	 * @param action the modification action
	 * @param condition a precondition for the modification action
	 * (if this condition does not apply, the action can be skipped)
	 */
	private void updateManualSingletonNames(Consumer<Set<String>> action, Predicate<Set<String>> condition) {
		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			// TODO 开始bean初始化动作时,需要同步的对手动注册的bean进行删除操作
			synchronized (this.beanDefinitionMap) {
				if (condition.test(this.manualSingletonNames)) {
					Set<String> updatedSingletons = new LinkedHashSet<>(this.manualSingletonNames);
					action.accept(updatedSingletons);
					this.manualSingletonNames = updatedSingletons;
				}
			}
		}
		else {
			// Still in startup registration phase
			if (condition.test(this.manualSingletonNames)) {
				action.accept(this.manualSingletonNames);
			}
		}
	}

	/**
	 * Remove any assumptions about by-type mappings.
	 */
	private void clearByTypeCache() {
		this.allBeanNamesByType.clear();
		this.singletonBeanNamesByType.clear();
	}


	//---------------------------------------------------------------------
	// Dependency resolution functionality
	//---------------------------------------------------------------------

	@Override
	public <T> NamedBeanHolder<T> resolveNamedBean(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		// TODO 先得到类型的原生类型, 然后再根据原生类型, 唯一的bean, 封装为NamedBeanHolder返回. 这个过程会拿出标有@Primary注解的bean,
		//  没有@Primary注解时, 会拿出同类型下优先级最高的bean. 如果出现多个同级bean, 会抛出NoUniqueBeanDefinitionException异常
		NamedBeanHolder<T> namedBean = resolveNamedBean(ResolvableType.forRawClass(requiredType), null, false);
		if (namedBean != null) {
			return namedBean;
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof AutowireCapableBeanFactory) {
			// TODO 如果在当前容器中没有找到需要的类型所对应的bean, 则到父容器去找
			return ((AutowireCapableBeanFactory) parent).resolveNamedBean(requiredType);
		}
		// TODO 还找不到, 就会抛出NoSuchBeanDefinitionException异常了
		throw new NoSuchBeanDefinitionException(requiredType);
	}

	// TODO
	@SuppressWarnings("unchecked")
	@Nullable
	private <T> NamedBeanHolder<T> resolveNamedBean(
			ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) throws BeansException {

		Assert.notNull(requiredType, "Required type must not be null");
		// TODO 根据类型取得对应的bean集合
		String[] candidateNames = getBeanNamesForType(requiredType);

		if (candidateNames.length > 1) {
			List<String> autowireCandidates = new ArrayList<>(candidateNames.length);
			for (String beanName : candidateNames) {
				if (!containsBeanDefinition(beanName) || getBeanDefinition(beanName).isAutowireCandidate()) {
					// TODO 迭代所有符合要求的bean, 把没有注册到容器中的, 或者允许自动装配的bean加入到自动装配候选中
					autowireCandidates.add(beanName);
				}
			}
			if (!autowireCandidates.isEmpty()) {
				candidateNames = StringUtils.toStringArray(autowireCandidates);
			}
		}

		if (candidateNames.length == 1) {
			String beanName = candidateNames[0];
			// TODO 只有唯一候选时, 通过getBean()方法进行实例化, 然后包装为NamedBeanHolder返回
			return new NamedBeanHolder<>(beanName, (T) getBean(beanName, requiredType.toClass(), args));
		}
		else if (candidateNames.length > 1) {
			// TODO 有多个结果时, 就需要进行过滤了
			Map<String, Object> candidates = new LinkedHashMap<>(candidateNames.length);
			for (String beanName : candidateNames) {
				if (containsSingleton(beanName) && args == null) {
					// TODO 把所有无参的单例bean进行初始化, 然后把初始化后的实例(NullBean时为null)放到候选集合中
					Object beanInstance = getBean(beanName);
					candidates.put(beanName, (beanInstance instanceof NullBean ? null : beanInstance));
				}
				else {
					// TODO 其他scope的bean, 直接根据bean名取得其类型, 也加入到候选集合中
					candidates.put(beanName, getType(beanName));
				}
			}
			// TODO 确定出首选bean
			String candidateName = determinePrimaryCandidate(candidates, requiredType.toClass());
			if (candidateName == null) {
				// TODO 没有的话把候选bean中优先级最高的bean拿出来
				candidateName = determineHighestPriorityCandidate(candidates, requiredType.toClass());
			}
			if (candidateName != null) {
				// TODO 取得对应的bean实例, 并封装为NamedBeanHolder返回
				Object beanInstance = candidates.get(candidateName);
				if (beanInstance == null || beanInstance instanceof Class) {
					// TODO 没有对应的bean实例, 或bean实例是Class时, 进行初始化
					beanInstance = getBean(candidateName, requiredType.toClass(), args);
				}
				return new NamedBeanHolder<>(candidateName, (T) beanInstance);
			}
			if (!nonUniqueAsNull) {
				// TODO 不支持null实例时, 抛出NoUniqueBeanDefinitionException异常
				throw new NoUniqueBeanDefinitionException(requiredType, candidates.keySet());
			}
		}
		// TODO 没法解析时, 返回null
		return null;
	}

	/**
	 *
	 * @param descriptor the descriptor for the dependency (field/method/constructor) 字段, 方法, 或构造函数所表示的, 用来
	 *                      描述一个用于注入的依赖项
	 * @param requestingBeanName the name of the bean which declares the given dependency 要创建实例的bean, 即, 要得到的bean
	 * @param autowiredBeanNames a Set that all names of autowired beans (used for
	 * resolving the given dependency) are supposed to be added to 自动装配过的bean集合
	 * @param typeConverter the TypeConverter to use for populating arrays and collections 类型转换器
	 * @return
	 * @throws BeansException
	 */
	@Override
	@Nullable
	// TODO 解决依赖关系
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {
		// TODO 将当前容器里方法参数名的解析策略设置到依赖描述的待注入项中(这个待注入项可能是字段, 方法参数(工厂方法, 或构造函数的参数))
		//  AbstractAutowireCapableBeanFactory容器默认解析策略是DefaultParameterNameDiscoverer
		descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());
		if (Optional.class == descriptor.getDependencyType()) {
			// TODO 如果依赖描述的待注入项(字段, 方法参数(工厂方法, 或构造函数的参数))是Optional类型, 将依赖描述的待注入项重新包装为一个
			//  NestedDependencyDescriptor, 然后再调用doResolveDependency(DependencyDescriptor, @Nullable String, @Nullable Set<String>, @Nullable TypeConverter)
			//  解析. 解析结果会被包装为一个Optional返回.
			//  比如: private Optional<GenericBean<Object, Object>> objectGenericBean; 类型会变为Optional[GenericBean(t=obj1, w=2)]
			return createOptionalDependency(descriptor, requestingBeanName);
		}
		else if (ObjectFactory.class == descriptor.getDependencyType() ||
				ObjectProvider.class == descriptor.getDependencyType()) {
			// TODO 如果依赖描述的待注入项(字段, 方法参数(工厂方法, 或构造函数的参数))是ObjectFactory或ObjectProvider类型时(Spring4.3
			//  提供的接口, 工厂类型), 创建一个DependencyObjectProvider对象返回
			return new DependencyObjectProvider(descriptor, requestingBeanName);
		}
		else if (javaxInjectProviderClass == descriptor.getDependencyType()) {
			// TODO 如果依赖描述的待注入项(字段, 方法参数(工厂方法, 或构造函数的参数))是JSR330的情况, 用JSR330创建一个Provider并返回
			return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
		}
		else {
			// TODO 其他情况会根据解析器的不同进行不同的处理:
			//  1. SimpleAutowireCandidateResolver: 默认解析器, 什么都不做, 返回null. Spring 2.5加入的; 实际上
			//     AutowireCandidateResolver接口已经将getLazyResolutionProxyIfNecessary()方法置为默认方法(Java 8新特性), 直接返回null.
			//  2. ContextAnnotationAutowireCandidateResolver: 处理@Lazy注解, 如果字段或方法上包含@Lazy, 表示为一个懒加载,
			//     Spring不会立即创建注入属性的实例, 而是生成代理对象来代替实例返回. 没有@Lazy注解则返回null
			Object result = getAutowireCandidateResolver().getLazyResolutionProxyIfNecessary(
					descriptor, requestingBeanName);
			if (result == null) {
				// TODO 不是懒加载时(不包含@Lazy), 开始解析依赖注入项并返回. 对于数组, Collection, Map等类型返回的会是一个包含多个
				//  候选bean的数组
				result = doResolveDependency(descriptor, requestingBeanName, autowiredBeanNames, typeConverter);
			}
			return result;
		}
	}

	/**
	 *
	 * @param descriptor 字段, 方法, 或构造函数所表示的依赖描述的待注入项
	 * @param beanName 要创建实例的bean, 即, 要得到的bean
	 * @param autowiredBeanNames 自动装配过的bean集合
	 * @param typeConverter 类型转换器
	 * @return
	 * @throws BeansException
	 */
	@Nullable
	// TODO 解析依赖, 这里会解析@Value, @Autowire这些
	public Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {
		// TODO 保存当前注入点, 用于处理结束后恢复现场
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			Object shortcut = descriptor.resolveShortcut(this);
			if (shortcut != null) {
				// TODO 依赖项是ShortcutDependencyDescriptor类型才能解析出值, 这时直接返回就好
				return shortcut;
			}
			// TODO 取得依赖描述的待注入项的类型(字段类型, 方法参数(工厂方法, 或构造函数的参数)类型), 这里会处理泛型
			Class<?> type = descriptor.getDependencyType();
			// TODO 下面开始处理@Value注解. AutowireCandidateResolver接口的默认方法getSuggestedValue()用来取得依赖描述的待注入项
			//  上@Value注解中的value值:
			//  1. AutowireCandidateResolver: 提供了默认方法, 返回null
			//  2. SimpleAutowireCandidateResolver: 是AutowireCandidateResolver接口的实现类, 返回null. Java 8后接口提供了默认
			//     类特性, 实现类可以不再提供默认实现, 这里是为了向后兼容而保留
			//  3. QualifierAnnotationAutowireCandidateResolver: 处理@Value注解, 取得其中值, 如果值为null, 再取使用方法参数
			//     (工厂方法, 或构造函数的参数)的方法上的@Value的值
			Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
			if (value != null) {
				// TODO 解析出了@Value值, 就要对其中的属性进行解析了
				if (value instanceof String) {
					// TODO 如果是String类型, 用StringValueResolver解析@Value中的占位符(${}).
					//  TIPS: 这里有个要注意的地方, 当没有设置PlaceholderConfigurerSupport的子类时, Spring容器会提供一个支持解析
					//        非法字符的StringValueResolver类型解析器. 遇到非法占位符时会原样输出.
					//        但是如果手动指定过PlaceholderConfigurerSupport的子类, 并且没有设置明确设置支持非法占位符时, 遇到
					//        非法占位符时, 会抛出异常. Spring Boot环境下添加的就是不支持非法占位符的PlaceholderConfigurerSupport
					String strVal = resolveEmbeddedValue((String) value);
					// TODO 要取得的bean存在于容器中时, 取得bean对应的mbd
					BeanDefinition bd = (beanName != null && containsBean(beanName) ?
							getMergedBeanDefinition(beanName) : null);
					// TODO 上面解析了@Value中的占位符, 解析结果可能是一个字面量, 或SpEL表达式, 或Resource等, 这时再用
					//  StandardBeanExpressionResolver处理SpEL, 或Resources等非字面量的情况
					value = evaluateBeanDefinitionString(strVal, bd);
				}
				// TODO 当没有提供类型转换器时, 会看是否有自定义的类型转换器, 如果没有直接使用默认的类型转换器SimpleTypeConverter
				TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
				try {
					// TODO 将解析结果转换为依赖描述的待注入项所指定的类型
					return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
				}
				catch (UnsupportedOperationException ex) {
					// A custom TypeConverter which does not support TypeDescriptor resolution...
					// TODO 转换失败时, 转换为依赖描述的待注入项的字段, 或方法参数(工厂方法, 或构造函数的参数)的类型
					return (descriptor.getField() != null ?
							converter.convertIfNecessary(value, type, descriptor.getField()) :
							converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
				}
			}
			// TODO 如果没有@Value注解, 就开始准备自动装配@Autowire的情况了.
			//  数组, Collection, Map等类型也支持自动注入, Spring会直接把符合类型的bean都注入到数组或容器中，处理逻辑是：
			//  1.确定容器或数组的组件类型, 对其分别进行处理
			//  2.调用findAutowireCandidates(核心方法)方法，获取与组件类型匹配的Map(beanName -> bean实例)
			//  3.将符合beanNames添加到autowiredBeanNames中
			Object multipleBeans = resolveMultipleBeans(descriptor, beanName, autowiredBeanNames, typeConverter);
			if (multipleBeans != null) {
				// TODO 找到直接返回依赖
				return multipleBeans;
			}
			// TODO 到这里就是处理非数组, Collection, Map等类型了. 从容器中取得所有类型相同的bean
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			if (matchingBeans.isEmpty()) {
				if (isRequired(descriptor)) {
					// TODO 如果依然没有找到候选bean, 并且属性还是必须的. 这时就会抛出没有找到合适的bean的异常了:
					//  NoSuchBeanDefinitionException: expected at least 1 bean which qualifies as autowire candidate...
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				return null;
			}

			String autowiredBeanName;
			Object instanceCandidate;

			if (matchingBeans.size() > 1) {
				// TODO 对于集合, 数组类的多值自动装配在上面已经处理完并返回了. 能到这里已经全部是单值注入的情况了. 这种情况下,
				//  Spring注入时只能注入唯一的bean. 如果找到了多个候选bean, 确定一个唯一候选bean
				autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
				if (autowiredBeanName == null) {
					// TODO 没找到候选bean时
					if (isRequired(descriptor) || !indicatesMultipleBeans(type)) {
						// TODO 如果注入项是必须的, 或者不是Array, Collection, Map等类型, 就抛出NoUniqueBeanDefinitionException异常
						return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);
					}
					else {
						// In case of an optional Collection/Map, silently ignore a non-unique case:
						// possibly it was meant to be an empty collection of multiple regular beans
						// (before 4.3 in particular when we didn't even look for collection beans).
						// TODO 注入项不是必须的, 即: required=false, 或者注入项的类型就是Array, Collection, Map等类型时, 可以
						//  注入null, 不用抛出异常. 这是Spring4.3之后加入的功能
						return null;
					}
				}
				// TODO 拿出唯一候选bean
				instanceCandidate = matchingBeans.get(autowiredBeanName);
			}
			else {
				// We have exactly one match.
				// TODO 找到的是唯一候选项时, 拿出bean及其对应的实例
				Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
				autowiredBeanName = entry.getKey();
				instanceCandidate = entry.getValue();
			}

			if (autowiredBeanNames != null) {
				// TODO 把找到的bean加入到已装配的bean缓存中
				autowiredBeanNames.add(autowiredBeanName);
			}
			if (instanceCandidate instanceof Class) {
				// TODO 在当前容器中实例化候选类
				instanceCandidate = descriptor.resolveCandidate(autowiredBeanName, type, this);
			}
			Object result = instanceCandidate;
			if (result instanceof NullBean) {
				if (isRequired(descriptor)) {
					// TODO 如果依然没有找到候选bean, 并且属性还是必须的. 这时就会抛出没有找到合适的bean的异常了:
					//  NoSuchBeanDefinitionException: expected at least 1 bean which qualifies as autowire candidate...
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				result = null;
			}
			if (!ClassUtils.isAssignableValue(type, result)) {
				// TODO 类型不同时, 抛出BeanNotOfRequiredTypeException异常
				throw new BeanNotOfRequiredTypeException(autowiredBeanName, type, instanceCandidate.getClass());
			}
			// TODO 没问题时, 返回实例化后的用于注入的bean
			return result;
		}
		finally {
			// TODO 恢复注入点
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	/**
	 * 用来对集合, 数组, 或Map这种多值类型的注入
	 *
	 * @param descriptor 字段, 方法, 或构造函数所表示的依赖描述的待注入项
	 * @param beanName 要创建实例的bean, 即, 要得到的bean
	 * @param autowiredBeanNames 自动装配过的bean集合
	 * @param typeConverter 类型转换器
	 * @return
	 */
	@Nullable
	private Object resolveMultipleBeans(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {
		// TODO 取得依赖描述的待注入项的类型(字段类型, 方法参数(工厂方法, 或构造函数的参数)类型), 这里会处理泛型.
		//  Spring支持数组、Collection、Map等类型的自动注入:
		//    1. 数组类型: int[]时, 得到的类型就是int[]
		//    2. 集合类型: List<>时, 得到的是List<>
		//    3. Map类型: Map<>时, 得到的是Map<>
		//  TIPS: 这里只是得到依赖描述的待注入项的声明类型, 而不是具体的组件类型, 比如int[], 这里得到的只是int[], 而不是int
		final Class<?> type = descriptor.getDependencyType();
		// TODO 下面是按依赖描述的待注入项的类型进行解析
		if (descriptor instanceof StreamDependencyDescriptor) {
			// TODO 依赖描述的待注入项是流的情况, 根据依赖注入项的名字, 类型寻找自动注入候选类
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			if (autowiredBeanNames != null) {
				// TODO 如果有自动装配过的bean集合时, 将找到的候选bean全部放进去
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			// TODO 通过DependencyDescriptor#resolveCandidate()方法, 在当前容器中挨个实例化候选类, 同时过滤掉NullBean
			Stream<Object> stream = matchingBeans.keySet().stream()
					.map(name -> descriptor.resolveCandidate(name, type, this))
					.filter(bean -> !(bean instanceof NullBean));
			if (((StreamDependencyDescriptor) descriptor).isOrdered()) {
				// TODO 如果支持排序, 则对候选类再进行排序, 然后返回
				stream = stream.sorted(adaptOrderComparator(matchingBeans));
			}
			return stream;
		}
		else if (type.isArray()) {
			// TODO 依赖描述的待注入项是数组的情况, 取得依赖描述的待注入项的组件类型. 比如: int[] field时, 数组的组件类型就是int
			Class<?> componentType = type.getComponentType();
			// TODO 然后再取得依赖描述的待注入项的包装类型(ResolvableType). 比如: int[] field时, 得到的是包装成ResolvableType的int[]
			ResolvableType resolvableType = descriptor.getResolvableType();
			// TODO 解析数组的类型. 这里会做一个判断, 如果依赖描述的待注入项的类型无法解析时, 使用依赖描述的待注入项的声明类型进行替代
			Class<?> resolvedArrayType = resolvableType.resolve(type);
			if (resolvedArrayType != type) {
				// TODO 当解析过的数组类型与依赖描述的待注入项的类型不同时, 用依赖描述的待注入项的包装类型的组件类型替换组件类型
				componentType = resolvableType.getComponentType().resolve();
			}
			if (componentType == null) {
				// TODO 没解析出组件类型, 返回null
				return null;
			}
			// TODO 根据依赖描述的待注入项的名字, 组件类型, 以及MultiElementDescriptor来寻找自动注入候选类
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, componentType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				// TODO 没找到时, 返回null
				return null;
			}
			if (autowiredBeanNames != null) {
				// TODO 如果有自动装配过的bean集合时, 将找到的候选bean全部放进去
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			// TODO 进行必要的类型转换, 会将所有匹配的bean转换成数组类型
			Object result = converter.convertIfNecessary(matchingBeans.values(), resolvedArrayType);
			if (result instanceof Object[]) {
				// TODO 对于Object[]类型, 取得比较器, 然后根据比较器进行排序
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				if (comparator != null) {
					Arrays.sort((Object[]) result, comparator);
				}
			}
			// TODO 最后返回处理好的结果
			return result;
		}
		else if (Collection.class.isAssignableFrom(type) && type.isInterface()) {
			// TODO 依赖描述的待注入项的是集合时, 比如:List<String>
			//  1. 通过getResolvableType()取得依赖描述的待注入项(可能是field或方法)对应的ResolvableType
			//  2. 通过asCollection()将ResolvableType转换成集合类型
			//  3. 然后通过resolveGeneric()方法解析取得集合, 返回的是集合中第一个参数的类型, 即String, 做为集合类型
			Class<?> elementType = descriptor.getResolvableType().asCollection().resolveGeneric();
			if (elementType == null) {
				return null;
			}
			// TODO 然后根据这个类型, 依赖描述的待注入项的名字, 以及MultiElementDescriptor来寻找自动注入候选类
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, elementType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				// TODO 没找到时, 返回null
				return null;
			}
			if (autowiredBeanNames != null) {
				// TODO 如果有自动装配过的bean集合时, 将找到的候选bean全部放进去
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			// TODO 将匹配的bean转换成需要的类型(集合类型)
			Object result = converter.convertIfNecessary(matchingBeans.values(), type);
			if (result instanceof List) {
				if (((List<?>) result).size() > 1) {
					// TODO 如果转换成了List, 取得比较器, 并根据比较器进行排序
					Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
					if (comparator != null) {
						((List<?>) result).sort(comparator);
					}
				}
			}
			// TODO 最后返回处理好的匹配的bean
			return result;
		}
		else if (Map.class == type) {
			// TODO 依赖描述的待注入项是map时, 比如: Map<String, Type>时:
			//  1. 通过getResolvableType()取得依赖描述的待注入项(可能是field或方法)对应的ResolvableType
			//  2. 用asMap()将ResolvableType转换为Map类型
			ResolvableType mapType = descriptor.getResolvableType().asMap();
			// TODO 然后通过resolveGeneric(int)方法解析取得Map, 取得Key的Type(参数0的作用就是取得第一个参数, 即String)
			Class<?> keyType = mapType.resolveGeneric(0);
			if (String.class != keyType) {
				// TODO 注入时只处理Key为String的情况(bean名对应类型), 其他情况不做处理.
				return null;
			}
			// TODO 再取得Map中Value的Type(参数1表示第二个参数, 即Type)
			Class<?> valueType = mapType.resolveGeneric(1);
			if (valueType == null) {
				// TODO 没有找到value的Type时, 返回空, 这种可能有么????
				return null;
			}
			// TODO 然后根据这个类型, 依赖描述的待注入项的名字, 以及MultiElementDescriptor来寻找自动注入候选类
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, valueType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				// TODO 没找到时, 返回null
				return null;
			}
			if (autowiredBeanNames != null) {
				// TODO 如果有自动装配过的bean集合时, 将找到的候选bean全部放进去
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			// TODO 返回找到的所有匹配的bean
			return matchingBeans;
		}
		else {
			// TODO 非集合, 数组, Map时, 返回null
			return null;
		}
	}

	private boolean isRequired(DependencyDescriptor descriptor) {
		return getAutowireCandidateResolver().isRequired(descriptor);
	}

	// TODO 是否为数组, 集合, 或者map类型
	private boolean indicatesMultipleBeans(Class<?> type) {
		return (type.isArray() || (type.isInterface() &&
				(Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))));
	}

	@Nullable
	private Comparator<Object> adaptDependencyComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator) {
			return ((OrderComparator) comparator).withSourceProvider(
					createFactoryAwareOrderSourceProvider(matchingBeans));
		}
		else {
			return comparator;
		}
	}

	private Comparator<Object> adaptOrderComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> dependencyComparator = getDependencyComparator();
		OrderComparator comparator = (dependencyComparator instanceof OrderComparator ?
				(OrderComparator) dependencyComparator : OrderComparator.INSTANCE);
		return comparator.withSourceProvider(createFactoryAwareOrderSourceProvider(matchingBeans));
	}

	private OrderComparator.OrderSourceProvider createFactoryAwareOrderSourceProvider(Map<String, ?> beans) {
		IdentityHashMap<Object, String> instancesToBeanNames = new IdentityHashMap<>();
		beans.forEach((beanName, instance) -> instancesToBeanNames.put(instance, beanName));
		return new FactoryAwareOrderSourceProvider(instancesToBeanNames);
	}

	/**
	 * Find bean instances that match the required type.
	 * Called during autowiring for the specified bean.
	 * @param beanName the name of the bean that is about to be wired 要创建实例的bean, 即, 要得到的bean
	 * @param requiredType the actual type of bean to look for 依赖描述的待注入项的类型, 可能是个组件类型
	 * (may be an array component type or collection element type)
	 * @param descriptor the descriptor of the dependency to resolve 字段, 方法, 或构造函数所表示的依赖描述的待注入项, 对于多值
	 *                   类型, 这里会是被包装过的包含多元素的MultiElementDescriptor
	 * @return a Map of candidate names and candidate instances that match
	 * the required type (never {@code null})
	 * @throws BeansException in case of errors
	 * @see #autowireByType
	 * @see #autowireConstructor
	 */
	// TODO 从容器中查找所有符合指定类型的自动注入候选, 过程是:
	//  1. 先看是否为容器自动加入的4个类型之一: BeanFactory, ResourceLoader, ApplicationEventPublisher, ApplicationContext
	//  2. 再看容器中所有指定类型的bean(包括父容器中指定类型的bean)
	protected Map<String, Object> findAutowireCandidates(
			@Nullable String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {
		// TODO 根据依赖描述的待注入项的类型, 在当前容器及其父容器中查找同类型的候选bean, 包括非单例的bean
		String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				this, requiredType, true, descriptor.isEager());
		Map<String, Object> result = new LinkedHashMap<>(candidateNames.length);
		// TODO 首先, 看依赖描述的待注入项的类型是否为容器启动时自动配置的特殊依赖bean, 即: 容器类型的bean. 容器类型的bean会直接加入到自动装配候选结果集中.
		//  在容器启动时, AbstractApplication在prepareBeanFactory()方法中自动为resolvableDependencies缓存添加了4个容器类型的依赖bean:
		//    1. BeanFactory: 容器本身, 这个时候ApplicationContext就有了BeanFactory的所有功能了
		//    2. ResourceLoader: 初始化的ApplicationContext本身
		//    3. ApplicationEventPublisher: 初始化的ApplicationContext本身
		//    4. ApplicationContext: 初始化的ApplicationContext本身
		for (Map.Entry<Class<?>, Object> classObjectEntry : this.resolvableDependencies.entrySet()) {
			// TODO 先迭代所有解析好的依赖关系, 取得容器类型的依赖项做为自动装配的类型, 即上面说的4种类型
			Class<?> autowiringType = classObjectEntry.getKey();
			if (autowiringType.isAssignableFrom(requiredType)) {
				// TODO 一旦依赖描述的待注入项的类型与容器类型依赖项的类型相同, 或容器类型依赖项的类型为bean类型的父类时,
				//  表示匹配成功, 这时会取得容器自动配置的依赖项(引用所对应的Object对象, 即Entry中的value)用于自动装配
				Object autowiringValue = classObjectEntry.getValue();
				// TODO 解析容器类型的依赖项:
				//  1. 如果不是工厂类型, 直接返回
				//  2. 如果是工厂类型
				//     a. 要得到的bean是个接口, 且用于注入的对象可序列化时, 创建一个代理返回
				//     b. 否则直接调用getObject()取得其代表的内容
				autowiringValue = AutowireUtils.resolveAutowiringValue(autowiringValue, requiredType);
				if (requiredType.isInstance(autowiringValue)) {
					// TODO 如果需要的类型是解析后自动装配项的实例时, 将自动装配项做为value加入到自动注入候选项的Map中, 其中Map的
					//  key为'方法名@对象的16进制字符串'. 这是个断路操作, 匹配到一个即退出
					result.put(ObjectUtils.identityToString(autowiringValue), autowiringValue);
					break;
				}
			}
		}
		// TODO 然后, 将候选bean中的自引用和不支持自动注入的bean剔除
		for (String candidate : candidateNames) {
			// TODO 迭代所有侯选bean, 进行以下判断:
			//  1. 是否为自引用(要取得的bean的名字与候选bean相同, 或在容器中的候选项的工厂与要得到的bean的名字相同, 即指定原始bean的一个工厂方法)
			//  2. 是否为自动注入的候选项: isAutowireCandidate(String, DependencyDescriptor)方法会检查@Qualifier注解, 泛型匹配等
			if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, descriptor)) {
				// TODO 满足以下条件的候选项会被尝试加入到自动注入候选项的Map结果集中:
				//  1. 不是自引用;
				//  2. 并且允许自动装配.
				//     2.1. BeanDefinition的autowireCandidate属性，表示是否允许该bean注入到其他bean中，默认为true
				//     2.2. 泛型类型的匹配, 如果存在的话
				//     2.3. @Qualifier存在时, 会直接比对@Qualifier中指定的beanName. 除了Spring自定义的@Qualifier, 还支持javax.inject.Qualifier注解
				//  以下候选bean会被加入到结果集:
				//  1. 待注入项是MultiElementDescriptor类型时(数组, 集合, Map), NullBean类型的侯选bean实例不会加入到结果集中;
				//  2. 单例以及支持排序的StreamDependencyDescriptor类型的待注入项, 遇到NullBean时会将null加入到结果集;
				//  3. 其他情况直接加入到结果集中
				addCandidateEntry(result, candidate, descriptor, requiredType);
			}
		}
		if (result.isEmpty()) {
			// TODO 经过对容器类型的依赖bean, 以及同类型的候选bean处理后, 如果结果集还是空的, Spring会放宽注入条件, 比如泛型不要求
			//  精确匹配, 允许自引用的注入等等. 下面的操作都是不支持数组, 集合, 或Map类型的, 所以这里先判断一下
			boolean multiple = indicatesMultipleBeans(requiredType);
			// Consider fallback matches if the first pass failed to find anything...
			// TODO forFallbackMatch()方法会生成一个新的, 放宽泛型类型验证的DependencyDescriptor用于后续自动注入项的查找
			DependencyDescriptor fallbackDescriptor = descriptor.forFallbackMatch();
			for (String candidate : candidateNames) {
				if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, fallbackDescriptor) &&
						(!multiple || getAutowireCandidateResolver().hasQualifier(descriptor))) {
					// TODO 和上面逻辑差不多, 只是增加了注入属性是非数组, 集合, Map类型, 或者依赖描述的注入项上有@Qualifier的条件
					addCandidateEntry(result, candidate, descriptor, requiredType);
				}
			}
			if (result.isEmpty() && !multiple) {
				// Consider self references as a final pass...
				// but in the case of a dependency collection, not the very same bean itself.
				// TODO 还是找不到, 并且注入属性是非数组, 集合, Map类型时, 再次放宽条件, 允许自引用
				for (String candidate : candidateNames) {
					if (isSelfReference(beanName, candidate) &&
							(!(descriptor instanceof MultiElementDescriptor) || !beanName.equals(candidate)) &&
							isAutowireCandidate(candidate, fallbackDescriptor)) {
						addCandidateEntry(result, candidate, descriptor, requiredType);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Add an entry to the candidate map: a bean instance if available or just the resolved
	 * type, preventing early bean initialization ahead of primary candidate selection.
	 *
	 * @param candidates 依赖注入候选bean的Map
	 * @param candidateName 依赖注入候选bean的名字
	 * @param descriptor 字段, 方法, 或构造函数所表示的依赖描述的待注入项, 对于多值类型, 这里会是被包装过的包含多元素的MultiElementDescriptor
	 * @param requiredType 依赖描述的待注入项的类型, 可能是个组件类型
	 */
	// TODO 对候选bean进行解析, 并把解析结果放到集合
	private void addCandidateEntry(Map<String, Object> candidates, String candidateName,
			DependencyDescriptor descriptor, Class<?> requiredType) {

		if (descriptor instanceof MultiElementDescriptor) {
			// TODO 依赖描述的待注入项是MultiElementDescriptor(数组, 集合, Map)时, 用依赖描述的待注入项来解析候选bean(其实就是从
			//  当前容器中取得候选项, 即this.getBean())
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			if (!(beanInstance instanceof NullBean)) {
				// TODO 将非空bean放入候选结果集
				candidates.put(candidateName, beanInstance);
			}
		}
		else if (containsSingleton(candidateName) || (descriptor instanceof StreamDependencyDescriptor &&
				((StreamDependencyDescriptor) descriptor).isOrdered())) {
			// TODO 以下两种情况时, 用依赖描述的待注入项去解析候选bean:
			//  1. 候选bean在单例缓存中;
			//  2. 待注入项是支持排序的StreamDependencyDescriptor类型时;
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			// TODO 这两种情况是支持空bean的, 所以如果取得的实例是NullBean, 则将null加入到候选结果集中
			candidates.put(candidateName, (beanInstance instanceof NullBean ? null : beanInstance));
		}
		else {
			// TODO 其他情况直接将候选bean加入到候选结果集中
			candidates.put(candidateName, getType(candidateName));
		}
	}

	/**
	 * Determine the autowire candidate in the given set of beans.
	 * <p>Looks for {@code @Primary} and {@code @Priority} (in that order).
	 * @param candidates a Map of candidate names and candidate instances
	 * that match the required type, as returned by {@link #findAutowireCandidates}
	 * @param descriptor the target dependency to match against
	 * @return the name of the autowire candidate, or {@code null} if none found
	 */
	@Nullable
	// TODO 从候选集合中确定唯一的自动注入bean, 会找@Primary和Priority注解
	protected String determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor descriptor) {
		Class<?> requiredType = descriptor.getDependencyType();
		// TODO 首选去看是否有@Primary注解标注的bean, 如果有直接返回
		String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
		if (primaryCandidate != null) {
			return primaryCandidate;
		}
		// TODO 没有@Primary注解时再看@Priority设置的等级, 找最高等级的返回
		String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
		if (priorityCandidate != null) {
			return priorityCandidate;
		}
		// Fallback
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateName = entry.getKey();
			Object beanInstance = entry.getValue();
			// TODO 还没找到, 就看候选bean里有没有与要注入的项同名的(包括别名), 有就直接返回.
			//  TIPS: 注入项如果是字段, descriptor.getDependencyName()会调用this.field.getName()直接使用字段名. 所以@Autowired
			//        虽然匹配到两个类型的bean, 即使没有使用@Qualifier注解, 也会根据字段名找到一个合适的(若没找到, 就报错)
			if ((beanInstance != null && this.resolvableDependencies.containsValue(beanInstance)) ||
					matchesBeanName(candidateName, descriptor.getDependencyName())) {
				return candidateName;
			}
		}
		// TODO 真没有的话, 就返回null了
		return null;
	}

	/**
	 * Determine the primary candidate in the given set of beans.
	 *
	 * @param candidates a Map of candidate names and candidate instances 所有候选bean
	 * (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against 要匹配的类型
	 * @return the name of the primary candidate, or {@code null} if none found
	 * @see #isPrimary(String, Object)
	 */
	@Nullable
	// TODO 解析@Primary注解, 确定首选bean
	protected String determinePrimaryCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String primaryBeanName = null;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (isPrimary(candidateBeanName, beanInstance)) {
				// TODO 找出候选bean中带有@Primary注解的bean
				if (primaryBeanName != null) {
					// TODO 有多个的情况下, 就要进行判断了. 先看候选bean是否注册在容器中了
					boolean candidateLocal = containsBeanDefinition(candidateBeanName);
					// TODO 然后再看得到的首选bean是否注册到容器中
					boolean primaryLocal = containsBeanDefinition(primaryBeanName);
					if (candidateLocal && primaryLocal) {
						// TODO 都注册过, 就会出现冲突, 抛出NoUniqueBeanDefinitionException异常
						throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
								"more than one 'primary' bean found among candidates: " + candidates.keySet());
					}
					else if (candidateLocal) {
						// TODO 首选bean没有注册在容器中时, 用候选bean对其进行替换
						primaryBeanName = candidateBeanName;
					}
				}
				else {
					primaryBeanName = candidateBeanName;
				}
			}
		}
		return primaryBeanName;
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a primary bean.
	 * @param beanName the name of the bean 要取得的bean
	 * @param beanInstance the corresponding bean instance (can be null) 没用过
	 * @return whether the given bean qualifies as primary
	 */
	// TODO 检查要操作的bean是否为首选项, 是否有@Primary注解
	protected boolean isPrimary(String beanName, Object beanInstance) {
		String transformedBeanName = transformedBeanName(beanName);
		if (containsBeanDefinition(transformedBeanName)) {
			return getMergedLocalBeanDefinition(transformedBeanName).isPrimary();
		}
		BeanFactory parent = getParentBeanFactory();
		return (parent instanceof DefaultListableBeanFactory &&
				((DefaultListableBeanFactory) parent).isPrimary(transformedBeanName, beanInstance));
	}

	/**
	 * Determine the candidate with the highest priority in the given set of beans.
	 * <p>Based on {@code @javax.annotation.Priority}. As defined by the related
	 * {@link org.springframework.core.Ordered} interface, the lowest value has
	 * the highest priority.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type 所有候选bean
	 * @param requiredType the target dependency type to match against 要匹配的类型
	 * @return the name of the candidate with the highest priority,
	 * or {@code null} if none found
	 * @see #getPriority(Object)
	 */
	@Nullable
	// TODO 取得所有候选bean中优先级最高的bean, @Priority注解
	protected String determineHighestPriorityCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String highestPriorityBeanName = null;
		Integer highestPriority = null;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (beanInstance != null) {
				Integer candidatePriority = getPriority(beanInstance);
				if (candidatePriority != null) {
					if (highestPriorityBeanName != null) {
						if (candidatePriority.equals(highestPriority)) {
							// TODO 出现相同优先级时, 会抛出NoUniqueBeanDefinitionException异常
							throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
									"Multiple beans found with the same priority ('" + highestPriority +
											"') among candidates: " + candidates.keySet());
						}
						else if (candidatePriority < highestPriority) {
							highestPriorityBeanName = candidateBeanName;
							highestPriority = candidatePriority;
						}
					}
					else {
						highestPriorityBeanName = candidateBeanName;
						highestPriority = candidatePriority;
					}
				}
			}
		}
		return highestPriorityBeanName;
	}

	/**
	 * Return the priority assigned for the given bean instance by
	 * the {@code javax.annotation.Priority} annotation.
	 * <p>The default implementation delegates to the specified
	 * {@link #setDependencyComparator dependency comparator}, checking its
	 * {@link OrderComparator#getPriority method} if it is an extension of
	 * Spring's common {@link OrderComparator} - typically, an
	 * {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}.
	 * If no such comparator is present, this implementation returns {@code null}.
	 * @param beanInstance the bean instance to check (can be {@code null})
	 * @return the priority assigned to that bean or {@code null} if none is set
	 */
	// TODO 取得bean实例的优先级
	@Nullable
	protected Integer getPriority(Object beanInstance) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator) {
			return ((OrderComparator) comparator).getPriority(beanInstance);
		}
		return null;
	}

	/**
	 * Determine whether the given candidate name matches the bean name or the aliases
	 * stored in this bean definition.
	 */
	// TODO 候选bean的名字是否与要得到的bean名相同, 包含所有的别名
	protected boolean matchesBeanName(String beanName, @Nullable String candidateName) {
		return (candidateName != null &&
				(candidateName.equals(beanName) || ObjectUtils.containsElement(getAliases(beanName), candidateName)));
	}

	/**
	 * Determine whether the given beanName/candidateName pair indicates a self reference,
	 * i.e. whether the candidate points back to the original bean or to a factory method
	 * on the original bean.
	 */
	// TODO 判断给定的bean或候选注入项是否为一个自引用(候选对象指向的是原始bean还是原始bean的一个工厂方法). 为自引用的情况是:
	//  1. 要取得的bean的名字与候选项相同;
	//  2. 或候选项在当前容器中, 且其工厂类与要得到的bean的名字相同, 即指定原始bean的一个工厂方法
	private boolean isSelfReference(@Nullable String beanName, @Nullable String candidateName) {
		return (beanName != null && candidateName != null &&
				(beanName.equals(candidateName) || (containsBeanDefinition(candidateName) &&
						// TODO 要取得的bean的名字与候选项的工厂方法是否相同(会调用getMergedLocalBeanDefinition()方法来为侯选项
						//  产生一个合并了双亲属性的mbd, 然后判断的是否指向这个mbd的工厂)
						beanName.equals(getMergedLocalBeanDefinition(candidateName).getFactoryBeanName()))));
	}

	/**
	 * Raise a NoSuchBeanDefinitionException or BeanNotOfRequiredTypeException
	 * for an unresolvable dependency.
	 */
	private void raiseNoMatchingBeanFound(
			Class<?> type, ResolvableType resolvableType, DependencyDescriptor descriptor) throws BeansException {

		checkBeanNotOfRequiredType(type, descriptor);

		throw new NoSuchBeanDefinitionException(resolvableType,
				"expected at least 1 bean which qualifies as autowire candidate. " +
				"Dependency annotations: " + ObjectUtils.nullSafeToString(descriptor.getAnnotations()));
	}

	/**
	 * Raise a BeanNotOfRequiredTypeException for an unresolvable dependency, if applicable,
	 * i.e. if the target type of the bean would match but an exposed proxy doesn't.
	 */
	private void checkBeanNotOfRequiredType(Class<?> type, DependencyDescriptor descriptor) {
		for (String beanName : this.beanDefinitionNames) {
			RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
			Class<?> targetType = mbd.getTargetType();
			if (targetType != null && type.isAssignableFrom(targetType) &&
					isAutowireCandidate(beanName, mbd, descriptor, getAutowireCandidateResolver())) {
				// Probably a proxy interfering with target type match -> throw meaningful exception.
				Object beanInstance = getSingleton(beanName, false);
				Class<?> beanType = (beanInstance != null && beanInstance.getClass() != NullBean.class ?
						beanInstance.getClass() : predictBeanType(beanName, mbd));
				if (beanType != null && !type.isAssignableFrom(beanType)) {
					throw new BeanNotOfRequiredTypeException(beanName, type, beanType);
				}
			}
		}

		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			((DefaultListableBeanFactory) parent).checkBeanNotOfRequiredType(type, descriptor);
		}
	}

	/**
	 * Create an {@link Optional} wrapper for the specified dependency.
	 */
	private Optional<?> createOptionalDependency(
			DependencyDescriptor descriptor, @Nullable String beanName, final Object... args) {
		// TODO Optional类型用于包装其他类型, 将依赖注入项包装为处理嵌套类的NestedDependencyDescriptor
		DependencyDescriptor descriptorToUse = new NestedDependencyDescriptor(descriptor) {
			@Override
			public boolean isRequired() {
				return false;
			}
			@Override
			// TODO 重写了resolveCandidate()方法
			public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
				// TODO 当有参数时, 从容器中取得指定类型参数的依赖项
				return (!ObjectUtils.isEmpty(args) ? beanFactory.getBean(beanName, args) :
						// TODO 没有参数时, 调用父类的方法, 直接从容器中取得依赖项
						super.resolveCandidate(beanName, requiredType, beanFactory));
			}
		};
		// TODO 然后开始解析依赖项, 最后返回一个Optional包装好的, 解析过的依赖对象
		Object result = doResolveDependency(descriptorToUse, beanName, null, null);
		return (result instanceof Optional ? (Optional<?>) result : Optional.ofNullable(result));
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(ObjectUtils.identityToString(this));
		sb.append(": defining beans [");
		sb.append(StringUtils.collectionToCommaDelimitedString(this.beanDefinitionNames));
		sb.append("]; ");
		BeanFactory parent = getParentBeanFactory();
		if (parent == null) {
			sb.append("root of factory hierarchy");
		}
		else {
			sb.append("parent: ").append(ObjectUtils.identityToString(parent));
		}
		return sb.toString();
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("DefaultListableBeanFactory itself is not deserializable - " +
				"just a SerializedBeanFactoryReference is");
	}

	protected Object writeReplace() throws ObjectStreamException {
		if (this.serializationId != null) {
			return new SerializedBeanFactoryReference(this.serializationId);
		}
		else {
			throw new NotSerializableException("DefaultListableBeanFactory has no serialization id");
		}
	}


	/**
	 * Minimal id reference to the factory.
	 * Resolved to the actual factory instance on deserialization.
	 */
	private static class SerializedBeanFactoryReference implements Serializable {

		private final String id;

		public SerializedBeanFactoryReference(String id) {
			this.id = id;
		}

		private Object readResolve() {
			Reference<?> ref = serializableFactories.get(this.id);
			if (ref != null) {
				Object result = ref.get();
				if (result != null) {
					return result;
				}
			}
			// Lenient fallback: dummy factory in case of original factory not found...
			DefaultListableBeanFactory dummyFactory = new DefaultListableBeanFactory();
			dummyFactory.serializationId = this.id;
			return dummyFactory;
		}
	}


	/**
	 * A dependency descriptor marker for nested elements.
	 */
	private static class NestedDependencyDescriptor extends DependencyDescriptor {

		public NestedDependencyDescriptor(DependencyDescriptor original) {
			super(original);
			increaseNestingLevel();
		}
	}


	/**
	 * A dependency descriptor for a multi-element declaration with nested elements.
	 */
	private static class MultiElementDescriptor extends NestedDependencyDescriptor {

		public MultiElementDescriptor(DependencyDescriptor original) {
			super(original);
		}
	}


	/**
	 * A dependency descriptor marker for stream access to multiple elements.
	 */
	private static class StreamDependencyDescriptor extends DependencyDescriptor {

		private final boolean ordered;

		public StreamDependencyDescriptor(DependencyDescriptor original, boolean ordered) {
			super(original);
			this.ordered = ordered;
		}

		public boolean isOrdered() {
			return this.ordered;
		}
	}


	private interface BeanObjectProvider<T> extends ObjectProvider<T>, Serializable {
	}


	/**
	 * Serializable ObjectFactory/ObjectProvider for lazy resolution of a dependency.
	 */
	private class DependencyObjectProvider implements BeanObjectProvider<Object> {

		private final DependencyDescriptor descriptor;

		private final boolean optional;

		@Nullable
		private final String beanName;

		public DependencyObjectProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			// TODO 将依赖注入项包装为NestedDependencyDescriptor类型
			this.descriptor = new NestedDependencyDescriptor(descriptor);
			this.optional = (this.descriptor.getDependencyType() == Optional.class);
			this.beanName = beanName;
		}

		@Override
		public Object getObject() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				Object result = doResolveDependency(this.descriptor, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		public Object getObject(final Object... args) throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName, args);
			}
			else {
				DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
					@Override
					public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
						return beanFactory.getBean(beanName, args);
					}
				};
				Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		@Nullable
		public Object getIfAvailable() throws BeansException {
			try {
				if (this.optional) {
					return createOptionalDependency(this.descriptor, this.beanName);
				}
				else {
					DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
						@Override
						public boolean isRequired() {
							return false;
						}
					};
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifAvailable(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfAvailable();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Override
		@Nullable
		public Object getIfUnique() throws BeansException {
			DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
				@Override
				public boolean isRequired() {
					return false;
				}
				@Override
				@Nullable
				public Object resolveNotUnique(ResolvableType type, Map<String, Object> matchingBeans) {
					return null;
				}
			};
			try {
				if (this.optional) {
					return createOptionalDependency(descriptorToUse, this.beanName);
				}
				else {
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifUnique(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfUnique();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Nullable
		protected Object getValue() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				return doResolveDependency(this.descriptor, this.beanName, null, null);
			}
		}

		@Override
		public Stream<Object> stream() {
			return resolveStream(false);
		}

		@Override
		public Stream<Object> orderedStream() {
			return resolveStream(true);
		}

		@SuppressWarnings("unchecked")
		private Stream<Object> resolveStream(boolean ordered) {
			DependencyDescriptor descriptorToUse = new StreamDependencyDescriptor(this.descriptor, ordered);
			Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
			return (result instanceof Stream ? (Stream<Object>) result : Stream.of(result));
		}
	}


	/**
	 * Separate inner class for avoiding a hard dependency on the {@code javax.inject} API.
	 * Actual {@code javax.inject.Provider} implementation is nested here in order to make it
	 * invisible for Graal's introspection of DefaultListableBeanFactory's nested classes.
	 */
	private class Jsr330Factory implements Serializable {

		public Object createDependencyProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			return new Jsr330Provider(descriptor, beanName);
		}

		private class Jsr330Provider extends DependencyObjectProvider implements Provider<Object> {

			public Jsr330Provider(DependencyDescriptor descriptor, @Nullable String beanName) {
				super(descriptor, beanName);
			}

			@Override
			@Nullable
			public Object get() throws BeansException {
				return getValue();
			}
		}
	}


	/**
	 * An {@link org.springframework.core.OrderComparator.OrderSourceProvider} implementation
	 * that is aware of the bean metadata of the instances to sort.
	 * <p>Lookup for the method factory of an instance to sort, if any, and let the
	 * comparator retrieve the {@link org.springframework.core.annotation.Order}
	 * value defined on it. This essentially allows for the following construct:
	 */
	private class FactoryAwareOrderSourceProvider implements OrderComparator.OrderSourceProvider {

		private final Map<Object, String> instancesToBeanNames;

		public FactoryAwareOrderSourceProvider(Map<Object, String> instancesToBeanNames) {
			this.instancesToBeanNames = instancesToBeanNames;
		}

		@Override
		@Nullable
		public Object getOrderSource(Object obj) {
			String beanName = this.instancesToBeanNames.get(obj);
			if (beanName == null || !containsBeanDefinition(beanName)) {
				return null;
			}
			RootBeanDefinition beanDefinition = getMergedLocalBeanDefinition(beanName);
			List<Object> sources = new ArrayList<>(2);
			Method factoryMethod = beanDefinition.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				sources.add(factoryMethod);
			}
			Class<?> targetType = beanDefinition.getTargetType();
			if (targetType != null && targetType != obj.getClass()) {
				sources.add(targetType);
			}
			return sources.toArray();
		}
	}

}
