/*
 * Copyright 2002-2022 the original author or authors.
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

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.AutowiredPropertyMarker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.NativeDetector;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;
import org.springframework.util.function.ThrowingSupplier;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 13.02.2004
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/** Strategy for creating bean instances. */
	private InstantiationStrategy instantiationStrategy;

	/** Resolver strategy for method parameter names. */
	@Nullable
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/** Whether to automatically try to resolve circular references between beans. */
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 */
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/** Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper. */
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/** Cache of candidate factory methods per factory class. */
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
		if (NativeDetector.inNativeImage()) {
			this.instantiationStrategy = new SimpleInstantiationStrategy();
		}
		else {
			this.instantiationStrategy = new CglibSubclassingInstantiationStrategy();
		}
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	protected InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	protected ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Return whether to allow circular references between beans.
	 * @since 5.3.10
	 * @see #setAllowCircularReferences
	 */
	public boolean isAllowCircularReferences() {
		return this.allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Return whether to allow the raw injection of a bean instance.
	 * @since 5.3.10
	 * @see #setAllowRawInjectionDespiteWrapping
	 */
	public boolean isAllowRawInjectionDespiteWrapping() {
		return this.allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory otherAutowireFactory) {
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use prototype bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition rbd) {
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		}
		else {
			Object bean = getInstantiationStrategy().instantiate(bd, null, this);
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	@Override
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// TODO 挨个执行后处理的postProcessBeforeInitialization方法, 在初始化bean之前, 对bean做处理
			Object current = processor.postProcessBeforeInitialization(result, beanName);
			if (current == null) {
				return result;
			}
			result = current;
		}
		return result;
	}

	@Override
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// TODO 挨个执行所有后处理器的postProcessAfterInitialization方法, 对初始化后的bean进行处理. 这是个很重要的方法, AOP,
			//  JNDI, 等实现都是基于后处理器的此方法
			Object current = processor.postProcessAfterInitialization(result, beanName);
			if (current == null) {
				// TODO 如果处理后返回的是null, 直接返回传入的bean对象
				return result;
			}
			// TODO 否则返回处理后的对象
			result = current;
		}
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(existingBean, getBeanPostProcessorCache().destructionAware).destroy();
	}


	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------

	@Override
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			return getBean(name, descriptor.getDependencyType());
		}
		finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}


	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * Central method of this class: creates a bean instance,
	 * populates the bean instance, applies post-processors, etc.
	 * @see #doCreateBean
	 *
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation 为bean设置的参数值, 只有构造方法
	 *             和工厂方法可以用此参数来给取得的bean赋值, 同时bean的scope也必需是prototype类型
	 * @return
	 * @throws BeanCreationException
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}
		RootBeanDefinition mbdToUse = mbd;

		// Make sure bean class is actually resolved at this point, and
		// clone the bean definition in case of a dynamically resolved Class
		// which cannot be stored in the shared merged bean definition.
		// TODO 解析类mbd持有的类. 如果mbd设置了class引用, 不再做任何处理，直接返回这个引用.
		//  否则, 通过ClassLoader.loadClass()或Class.ForName()对全限定名加载
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			// TODO 类加载成功后, 如果mbd的class属性设置的是全限定名, 用mbd复制一个新的mbd, 这个复制用的是深拷贝, 然后所解析好的引用
			//  做为新mbd的beanClass. 后面再处理时，用的就是这个复制的mbd了
			mbdToUse = new RootBeanDefinition(mbd);
			mbdToUse.setBeanClass(resolvedClass);
		}

		// Prepare method overrides.
		try {
			// TODO 处理bean的方法重载(@Lookup, <lookup-method />, <replace-method />)
			mbdToUse.prepareMethodOverrides();
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		try {
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			// TODO 返回一个目标类的代理(用于动态代理等). 如果是普通的bean, 这里返回的会是null. 容器里所有的
			//  InstantiationAwareBeanPostProcessors实例都会在此处生效, 同时进行前置处理
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
			if (bean != null) {
				// TODO 短路操作, 如果返回的是代理对象, 则创建结束, 直接返回
				return bean;
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}

		try {
			// TODO 开始正式创建bean实例, 然后返回创建好的bean实例
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		}
		catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// A previously detected exception with proper bean creation context already,
			// or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * Actually create the specified bean. Pre-creation processing has already happened
	 * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
	 * <p>Differentiates between default bean instantiation, use of a
	 * factory method, and autowiring a constructor.
	 *
	 * @param beanName the name of the bean 要得到的bean
	 * @param mbd the merged bean definition for the bean 要得到的bean的mbd
	 * @param args explicit arguments to use for constructor or factory method invocation 为bean设置的参数值, 只有构造方法
	 *             和工厂方法可以用此参数来给取得的bean赋值, 同时bean的scope也必需是prototype类型
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		// Instantiate the bean.
		// TODO 经过包含后的bean实例
		BeanWrapper instanceWrapper = null;
		if (mbd.isSingleton()) {
			// TODO 单例的情况, 尝试从 FactoryBean 的实例缓存取得bean实例
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		if (instanceWrapper == null) {
			// TODO 缓存中不存在bean实例时, 说明其不是一个 FactoryBean, 创建对应的bean实例
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		// TODO 取得被包装过的bean实例以及bean实例的类信息
		Object bean = instanceWrapper.getWrappedInstance();
		Class<?> beanType = instanceWrapper.getWrappedClass();
		if (beanType != NullBean.class) {
			// TODO bean实例的不是NullBean类型时, 将其做为解析后的目标类型, 放到mbd的resolvedTargetType属性中
			mbd.resolvedTargetType = beanType;
		}

		// Allow post-processors to modify the merged bean definition.
		synchronized (mbd.postProcessingLock) {
			// TODO 判断mbd是否被MergedBeanDefinitionPostProcessor类型的后处理器处理过
			if (!mbd.postProcessed) {
				try {
					// TODO 没被处理过, 就用MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition()方法
					//  对bean进行处理, 经过这里后, 自动注入, 生命周期等注解的方法或字段会被放入缓存, 后面填充属性时就可以使用了
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Post-processing of merged bean definition failed", ex);
				}
				// TODO 处理完成后, 标识为已处理
				mbd.postProcessed = true;
			}
		}

		// Eagerly cache singletons to be able to resolve circular references
		// even when triggered by lifecycle interfaces like BeanFactoryAware.
		// TODO 提前暴露可以解析循环依赖的问题. 这里先验证一下当前bean是否支持提前暴露. 如果当前bean是个单例的, 且允许循环引用时, 如果
		//  这个bean正处在创建过程中, 则表示其支持提前暴露. 支持提前暴露的bean会向singletonFactories添加一个objectFactory, 后期
		//  依赖该bean的其他bean都可以从singletonFactories中直接获取
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}
			// TODO 解决单例bean的循环引用是通过提前暴露bean来实现的. 如果bean支持提前暴露, 会通过addSingletonFactory()方法将其放到
			//  singletonFactories, 和registeredSingletons缓存中, 同时从earlySingletonObjects缓存中移除.
			//  提前暴露的bean有可能需要进行AOP代理, 所以SmartInstantiationAwareBeanPostProcessor#getEarlyBeanReference()方法
			//  会看容器中是否有可以应用于当前操作的bean的Advisor来决定是否为其创建动态代理. 基于接口的代理会用JDK的动态代理实现, 基于
			//  类的代理会用CGLIB来实现
			addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
		}

		// Initialize the bean instance.
		// TODO 到这为止, bean的实例化已经完成, 下面开始准备对实例化的bean进行初始化
		Object exposedObject = bean;
		try {
			// TODO 开始给bean填充属性
			populateBean(beanName, mbd, instanceWrapper);
			// TODO 进行初始化
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		}
		catch (Throwable ex) {
			if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
				throw (BeanCreationException) ex;
			}
			else {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
			}
		}

		if (earlySingletonExposure) {
			// TODO 支持提前暴露时, 拿出对应的单例bean
			Object earlySingletonReference = getSingleton(beanName, false);
			if (earlySingletonReference != null) {
				if (exposedObject == bean) {
					exposedObject = earlySingletonReference;
				}
				else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					// TODO 取得所有依赖此bean的依赖bean
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					for (String dependentBean : dependentBeans) {
						// TODO 遍历所有依赖bean, 如果依赖的bean正在创建, 就把依赖bean加入到列表里
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							actualDependentBeans.add(dependentBean);
						}
					}
					if (!actualDependentBeans.isEmpty()) {
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
								StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
								"] in its raw version as part of a circular reference, but has eventually been " +
								"wrapped. This means that said other beans do not use the final version of the " +
								"bean. This is often the result of over-eager type matching - consider using " +
								"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// Register bean as disposable.
		try {
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}

		return exposedObject;
	}

	@Override
	@Nullable
	// TODO 预测bean的type类型
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// TODO 确定bean的类型, 即: bean所引用的Class对象
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			// TODO 在有明确了的bean的类型, 且这个bean是由容器创建, 并且容器注册过InstantiationAwareBeanPostProcessor类型的初始
			//  化后处理器时, 开始类型预测.
			//  首先看一下要匹配的类型是否只有一个, 且是FactoryBean类型
			boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				// TODO 遍历所有的SmartInstantiationAwareBeanPostProcessor类型后处理器来预测目标类型, 以下后处理器实现了predictBeanType()方法:
				//  1. SmartInstantiationAwareBeanPostProcessor接口: 提供了一个默认实现, 返回的是null;
				//  2. InstantiationAwareBeanPostProcessorAdapter抽象类: 什么也没做, 也是返回null, 其实可以去掉这个方法, 直接使用接口中的默认方法;
				//  3. AbstractAutoProxyCreator抽象类: 从代理类型中查找
				//  4. ScriptFactoryPostProcessor类: 用脚本工厂来查找 mark 以后补上
				Class<?> predicted = bp.predictBeanType(targetType, beanName);
				if (predicted != null &&
						(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
					// TODO 只要有一个类型匹配上, 并且不是FactoryBean类型时, 就返回预测的类型, 这是个断路操作
					return predicted;
				}
			}
		}
		// TODO 其他情况直接返回确定了的bean的类型, 即: bean所引用的Class对象
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 */
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// TODO 从bean的mbd中取得bean所表示的Class对象
		Class<?> targetType = mbd.getTargetType();
		if (targetType == null) {
			// TODO 所表示的Class对象不存在时, 根据bean的不同类型来解析其所表示的Class对象:
			targetType = (mbd.getFactoryMethodName() != null ?
					// TODO bean是由工厂方法进行实例化时, 用工厂方法的返回类型做为bean的类型
					getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
					// TODO 其他情况直接取得bean的类型
					resolveBeanClass(mbd, beanName, typesToMatch));
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				// TODO 并没有指定匹配的类型时, 把解析后的bean所表示的Class放到resolvedTargetType缓存中
				mbd.resolvedTargetType = targetType;
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see #createBean
	 */
	@Nullable
	// TODO 用来获得在使用工厂方法时, 工厂方法的类型
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// TODO 从表示bean的mbd的缓存中取出用于实例化bean的工厂方法的返回类型
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		if (cachedReturnType != null) {
			// TODO 找到直接返回
			return cachedReturnType.resolve();
		}
		// TODO 工厂类中所有的用于实例化bean的工厂方法返回的类型都是一样的
		Class<?> commonType = null;
		// TODO 缓存里没有, 看看内省工厂方法有没有被缓存
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;

		if (uniqueCandidate == null) {
			// TODO 还是没有, 就表示第一次执行, 需要对工厂方法进行解析
			Class<?> factoryClass;
			// TODO 首先假设用于实例化bean的工厂方法是静态的
			boolean isStatic = true;
			// TODO 获取用于实例化bean的工厂类的名字
			String factoryBeanName = mbd.getFactoryBeanName();
			if (factoryBeanName != null) {
				// TODO 如果指定了工厂类(配置文件中配置了工厂类), 表示其为一个非静态工厂方法
				if (factoryBeanName.equals(beanName)) {
					// TODO 工厂类的名字不能和要实例化的bean同名
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				// TODO 取得工厂类的类型
				factoryClass = getType(factoryBeanName);
				// TODO 使用工厂类的工厂方法都是非静态的
				isStatic = false;
			}
			else {
				// Check declared factory method return type on bean class.
				// TODO 如果没有指定工厂类, 根据指定的类型(这个typesToMatch可能是空的, 在做自动装配时会为FactoryBean)来解析bean做为工厂类的类型
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}

			if (factoryClass == null) {
				// TODO 还是没解析出来, 返回null
				return null;
			}
			// TODO 取得Class, 会处理CGLIB的情况
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			// TODO 如果配置文件中定义了的构造bean时所需要的参数(由'constructor-arg'设置)时, 取得参数数量做为匹配值, 没有的话为0
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			// TODO 取得工厂类中定义所有的方法, 如果这些方法不在factoryMethodCandidateCache缓存中, 则将其加入缓存
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			for (Method candidate : candidates) {
				// TODO 遍历所有候选的方法
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					// TODO 找出工厂类中所有参数数量多于配置文件所指定的参数数量的静态工厂方法
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							// TODO 方法有泛型类型的参数时时, 需要对泛型进行处理. 取出该方法有类型变量参数(TypeVariable可以表示任
							//  何类型的泛型变量, 如：T、K、V等变量. 比如: method(T name, V value))时, 取得方法中所有参数的类型, T和V
							Class<?>[] paramTypes = candidate.getParameterTypes();
							String[] paramNames = null;
							// TODO 参数名探测器, 默认为DefaultParameterNameDiscoverer
							ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
							if (pnd != null) {
								// TODO 用探测器取得方法所有参数的名字. 有好多实现, 这个得慢慢看, 最后得到的就是参数名数组, name和value
								paramNames = pnd.getParameterNames(candidate);
							}
							// TODO 取得bean的构造器的参数. 如果没有, 则会创建一个ConstructorArgumentValues
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							// TODO 这里是所有使用过的参数值
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							Object[] args = new Object[paramTypes.length];
							for (int i = 0; i < args.length; i++) {
								// TODO 按顺序根据候选方法的参数值, 和参数名与要实例化bean所用的方法(工厂方法, 构造器)对应位置所使
								//  用的参数进行匹配. 如果完全一致, 就返回要实例化bean所使用的方法(工厂方法, 构造器)对应位置的参数值.
								//  如果不一致, 会用参数类型及参数名进行一次泛型类型参数的判断. 如果有一致的, 就返回. 没有就返回空.
								//  比如: 例子中就是用index = 0, paramType = T, paramName = name与要实例化bean所使用的方法
								//  (工厂方法, 构造器)的index = 0的位置的参数进行类型及类型名的匹配. 找不到的话, 就用这些到实例化
								//  bean所使用的方法(工厂方法, 构造器)的所有泛型参数中进行匹配.
								//  取得构造器的参数的值(先T, 后V), 并放到一个set中
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								if (valueHolder == null) {
									// TODO 如果没匹配上, 就放宽类型及名字的要求, 再次尝试从要实例化bean所使用的方法(工厂方法, 构造器)
									//  的泛型类型参数中进行匹配.
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								if (valueHolder != null) {
									// TODO 找到的话, 就将其放到参数数组, 以及使用过的参数集合中
									args[i] = valueHolder.getValue();
									usedValueHolders.add(valueHolder);
								}
							}
							// TODO 取得该方法的返回类型
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							// TODO 在没有通用type类型, 且上面得到的返回类型与该方法的返回类型相同时, 将该方法做为唯一候选方法
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							// TODO 取得returnType和commonType的通用类型
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								// TODO returnType与commonType没有通用类型时, 表示不可预测, 返回null
								return null;
							}
						}
						catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					}
					else {
						// TODO 方法没有泛型类型的参数时, 如果没有通用type, 就用该方法做为唯一候选方法, 否则为null
						uniqueCandidate = (commonType == null ? candidate : null);
						// TODO 和上面一样, 取得returnType和commonType的通用类型
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							// TODO returnType与commonType没有通用类型时, 表示不可预测, 返回null
							return null;
						}
					}
				}
			}
			// TODO 能走到这的会有两种情况:
			//  1. 上面的逻辑最终会匹配到一个方法, 然后将其做为mbd用于内省的工厂方法
			//  2. 工厂类里没有工厂方法, 这时uniqueCandidate为null, 然后mbd用于内省的工厂方法也就为null了
			mbd.factoryMethodToIntrospect = uniqueCandidate;
			if (commonType == null) {
				// TODO 对于情况1来说, 肯定不会走到这里. 只有情况2才会走到这里, 表示不可预测, 直接返回null
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		// TODO 有唯一的侯选工厂方法时, 使用唯一工厂方法的返回类型, 没有时使用通用类型(工厂类中所有的工厂方法返回的类型都是一样的)
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		// TODO 设置用于实例化bean的工厂方法的返回类型
		mbd.factoryMethodReturnType = cachedReturnType;
		// TODO 返回工厂类型的返回类型引用的Class对象
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
	 * full creation of the FactoryBean is used as fallback (through delegation to the
	 * superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		// TODO 从mbd的'factoryBeanObjectType'属性中取得类型
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			// TODO 不是NONE类型时, 直接返回
			return result;
		}
		// TODO 如果mbd对应是的class对象, 用bean的类型创建一个ResolvableType, 如果是全限定名, 则返回的是NONE
		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		if (mbd.getInstanceSupplier() != null) {
			// TODO 如果合并后的bean definition有回调函数, 尝试用两种方式取得类型:
			//  1. 通过mbd的目标类型
			//  2. 通过上面得到的beanType类型
			result = getFactoryBeanGeneric(mbd.targetType);
			if (result.resolve() != null) {
				return result;
			}
			result = getFactoryBeanGeneric(beanType);
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		// TODO 取得工厂bean和工厂方法的名字
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		if (factoryBeanName != null) {
			// TODO 工厂类存在的时的处理
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				// TODO 指定了工厂方法时, 通过工厂类名从注册中心beanDefinitionMap中取得工厂bd
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				if (factoryBeanDefinition instanceof AbstractBeanDefinition &&
						((AbstractBeanDefinition) factoryBeanDefinition).hasBeanClass()) {
					// TODO 工厂bd是AbstractBeanDefinition类型, 且其为class类型时, 直接使用对应的class类型
					factoryBeanClass = ((AbstractBeanDefinition) factoryBeanDefinition).getBeanClass();
				}
				else {
					// TODO 其他情况时, 得到合并了双亲属性的工厂类的mbd
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					// TODO 然后从工厂类的mbd中确定目标类型
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				if (factoryBeanClass != null) {
					// TODO 经过上面的处理后, 如果得到了工厂类的Class类型, 取得工厂类使用的工厂方法的类型
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						// TODO 如果类型已经解析过了, 直接返回上面取得的工厂类使用的工厂方法的返回类型
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				// TODO 上面没有解析出工厂方法的返回类型, 且工厂类没被创建过时, 返回NONE
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		if (allowInit) {
			// TODO 允许初始化时, 会根据mbd是否为单例取得工厂类
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				// TODO 有工厂类时, 取其类型
				Class<?> type = getTypeForFactoryBean(factoryBean);
				if (type != null) {
					// TODO 类型存在时, 包装为一个ResolvableType返回
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				// TODO 类型不存在时, 用AbstractBeanFactory#getTypeForFactoryBean()来初始化工厂类, 然后得到其类型后返回
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}
		// TODO mbd有静态工厂方法(没有工厂类名, 有工厂方法名的就是静态工厂方法), 且指定了class属性时
		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			// TODO 根据静态工厂方法的返回类型来确定工厂类的类型
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		// TODO 到这时, 表示以下情况:
		//  1. mbd中factoryBeanObjectType属性没有设置值
		//  2. 没有Supplier回调函数
		//  3. 没设置工厂类, 且还不是静态工厂方法
		//  4. 不允许初始化工厂类
		//  这时尝试用mbd中class属性指定的Class类型来取得类型
		result = getFactoryBeanGeneric(beanType);
		if (result.resolve() != null) {
			return result;
		}
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		if (type == null) {
			return ResolvableType.NONE;
		}
		// TODO 返回工厂类的泛型信息
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 * @param beanClass the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		// TODO 取得工厂类的原始Class类型
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		// TODO 将要使用的工厂方法包装为FactoryBeanMethodTypeFinder(指定了FactoryBeanMethodTypeFinder的factoryMethodName属性)
		//  FactoryBeanMethodTypeFinder的doWith()方法封装了获取方法返回类型的逻辑
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		// TODO 过滤所有的非桥接, 和合成方法, 然后调用FactoryBeanMethodTypeFinder的doWith()方法取得方法的返回类型(缓存在find的result中), 并返回
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		return finder.getResult();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param bean the raw bean instance
	 * @return the object to expose as bean reference
	 */
	// TODO 解析循环引用问题. 会对支持提前暴露的单例bean进行处理, 在需要的时候会为其创建代理
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		// TODO hasInstantiationAwareBeanPostProcessors()方法用来标记容器里是否有InstantiationAwareBeanPostProcessor的实现
		//  InstantiationAwareBeanPostProcessor接口的主要作用是在目标实例化前后, 对实例的属性进行处理
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			// TODO 当这个mbd(当前bean)是由容器创建的, 并且容器注册过用于对实例化阶段进行处理的InstantiationAwareBeanPostProcessor
			//  类型后处理器时, 尝试用这些处理器来对要创建的bean进行处理, 提前暴露出需要的bean, 来应对循环引用问题
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				// TODO 有三个地方实现了SmartInstantiationAwareBeanPostProcessor#getEarlyBeanReference(Object, String)方法:
				//  1. SmartInstantiationAwareBeanPostProcessor: 接口提供了一个默认实现方法, 直接返回当前bean做为要暴露的bean
				//  2. InstantiationAwareBeanPostProcessorAdapter: 抽象类, 实现与接口默认方法相同, Java 8后可以去掉了
				//  3. AbstractAutoProxyCreator: 抽象类, 用于提供为Spring AOP自动创建代理的功能
				exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
			}
		}
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			Object beanInstance = getSingleton(beanName, false);
			if (beanInstance instanceof FactoryBean) {
				return (FactoryBean<?>) beanInstance;
			}
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			}
			catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			}
			catch (BeanCreationException ex) {
				// Don't swallow a linkage error since it contains a full stacktrace on
				// first occurrence... and just a plain NoClassDefFoundError afterwards.
				if (ex.contains(LinkageError.class)) {
					throw ex;
				}
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			}
			finally {
				// Finished partial creation of this bean.
				afterSingletonCreation(beanName);
			}

			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		}
		catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		}
		catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		}
		finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}

		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 * @param mbd the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			// TODO 迭代所有实现了MergedBeanDefinitionPostProcessor接口的后处理器(此接口是个回调接口)的postProcessMergedBeanDefinition()
			//  方法(此方法提供了修改合并后的bd的属性, 缓存元数据信息的功能). MergedBeanDefinitionPostProcessor接口有以下实现:
			//  1. ApplicationListenerDetector: 将每个mbd是否为单例缓存到singletonNames中. 此实现在容器初始化时, 由以下两个位置注册到容器中:
			//     a. AbstractApplicationContext#prepareBeanFactory()方法中注册
			//     b. AbstractApplicationContext#registerBeanPostProcessors()最后会为探测嵌套bean而再次注入一个到容器中
			//  2. AutowiredAnnotationBeanPostProcessor: 提供自动装配功能, 处理被@Autowire, @Value, @Inject标注的字段或方法,
			//     为其生成注入点信息并加入injectionMetadataCache缓存中用于后续自动注入处理. 此实现在AnnotationConfigApplicationContext
			//     容器初始化时由AnnotatedBeanDefinitionReader创建
			//  3. InitDestroyAnnotationBeanPostProcessor: 处理bean的生命周期, 对于指定的注解(比如@PostConstruct, 或@PreDestroy)
			//     进行处理, 如果想自定义一些处理bean生命周期的处理器, 可以使用这个为基类. 其本身没有被Spring实例化, 用的都是其
			//     子类, 比如下面的CommonAnnotationBeanPostProcessor
			//  4. CommonAnnotationBeanPostProcessor: InitDestroyAnnotationBeanPostProcessor的子类, 除了设置了处理
			//     生命周期所支持的注解@PostConstruct和@PreDestroy, 还支持@Resource注解, 以及对@EJB, @WebServiceRef的支持.
			//     AnnotationConfigApplicationContext容器初始化时, 由AnnotatedBeanDefinitionReader创建
			//  5. JmsListenerAnnotationBeanPostProcessor: 什么都没做
			//  6. PersistenceAnnotationBeanPostProcessor: 处理持久化相关功能(@PersistenceContext和@PersistenceUnit注解),
			//     AnnotationConfigApplicationContext容器初始化时, 由AnnotatedBeanDefinitionReader创建
			//  7. RequiredAnnotationBeanPostProcessor: 已被废弃
			//  8. ScheduledAnnotationBeanPostProcessor: 什么都没做
			processor.postProcessMergedBeanDefinition(mbd, beanType, beanName);
		}
	}

	/**
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 */
	@Nullable
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		// TODO Spring的一个扩展点, 允许我们在bean实例化前, 和实例化后做一些处理, 这里主要是为bean生成代理
		Object bean = null;
		// TODO beforeInstantiationResolved是用来确定bean是否已经解析过了:
		//  true: 表示bean已经解析过了, 不需要再进行前置处理
		//  false: 表示bean还没有被解析过, 这时会在实例化前对bean definition做前置处理
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			// TODO hasInstantiationAwareBeanPostProcessors()方法用来标记容器里是否有InstantiationAwareBeanPostProcessor的实现
			//  InstantiationAwareBeanPostProcessor接口的主要作用是在目标实例化前后, 以及实例的属性进行处理
			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				// TODO 当这个mbd是由容器创建的, 并且容器注册过用于对实例化阶段进行处理的InstantiationAwareBeanPostProcessor
				//  类型后处理器时, 尝试从mbd中拿到bean的类型
				Class<?> targetType = determineTargetType(beanName, mbd);
				if (targetType != null) {
					// TODO 拿到了bean所表示的Class对象时, 用后处理器对其进行处理, 生成一个代理对象, 如果没有处理结果, 也不需要再进行实例化的后处理器了
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
					if (bean != null) {
						// TODO resolveBeforeInstantiation是个短路方法, 一旦生成了代理对象, 调用他的外层方法就会直接返回代理对象,
						//  而不再对bean进行其他处理(不会调用BeanPostProcessor后处理器的postProcessAfterInitialization()方法).
						//  所以Spring在这里为创建的代理应用了BeanPostProcessor后处理器, 让代理类在初始化后也可以被后处理器处理
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			// TODO 因为已经执行了本方法, 此时如果就表示bean已经解析过了
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName the name of the bean
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
			// TODO 迭代所有InstantiationAwareBeanPostProcessor类型的后处理器, 执行postProcessBeforeInstantiation()方法
			//  对bean进行处理, 只要有一个处理器产生了处理结果, 就直接返回, 目前有以下几个实现复写了postProcessBeforeInstantiation()方法:
			//  1. ScriptFactoryPostProcessor: 用脚本对象替换所有的工厂bean
			//  2. PersistenceAnnotationBeanPostProcessor: 返回null
			//  3. CommonAnnotationBeanPostProcessor: 返回null
			//  4. InstantiationAwareBeanPostProcessorAdapter: 返回null
			//  5. AbstractAutoProxyCreator: 用于创建代理对象
			//  6. ConfigurationClassPostProcessor$ImportAwareBeanPostProcessor: 对ImportAware类型的bean做了一下处理
			//  这边主要是处理AOP, 声明式事务等
			Object result = bp.postProcessBeforeInstantiation(beanClass, beanName);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	/**
	 * Create a new instance for the specified bean, using an appropriate instantiation strategy:
	 * factory method, constructor autowiring, or simple instantiation.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation 为bean设置的参数值, 只有构造方法
	 *             和工厂方法可以用此参数来给取得的bean赋值, 同时bean的scope也必需是prototype类型
	 * @return a BeanWrapper for the new instance
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
		// Make sure bean class is actually resolved at this point.
		// TODO 解析类, 通过ClassLoader.loadClass()或Class.ForName()对指定名字的类进行加载, 得到对应的引用. 根据类属性或类名加载
		//  resolveBeanClass()最后接收一个Class类型的可变长参数, 根据代码来看, 主要是为了支持AspectJ. 这些参数会排除在由LTW指定的
		//  自定义ClassLoader之外, 由JDK进行加载. 在创建实体时并没有指定这个参数
		Class<?> beanClass = resolveBeanClass(mbd, beanName);
		// TODO 确保class不为空, 并且访问权限为public. Spring无法创建非public对象, 会抛出BeanCreationException异常
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}
		// TODO 在用注解形式配置bean时, AnnotatedBeanDefinitionReader#registerBean()方法有三个重载设置过Supplier:
		//  1. registerBean(Class<T>, @Nullable Supplier<T>): Spring中没有调用过
		//  2. registerBean(Class<T>, @Nullable String, @Nullable Supplier<T>): Spring中没有调用过
		//  3. registerBean(Class<T>, @Nullable String, @Nullable Supplier<T>, BeanDefinitionCustomizer...): 这个重载被容器
		//       AnnotationConfigApplicationContext#registerBean(@Nullable String, Class<T>, @Nullable Supplier<T>, BeanDefinitionCustomizer...)
		//       进行了重载, 可以设置Supplier.
		//  这三个重载最终都会调用AnnotatedBeanDefinitionReader#doRegisterBean()来设置用于创建bean对象的Supplier
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		if (instanceSupplier != null) {
			// TODO 如果设置了mbd中的supplier属性, 从supplier中取得实例并包装为BeanWrapper返回
			return obtainFromSupplier(instanceSupplier, beanName);
		}

		if (mbd.getFactoryMethodName() != null) {
			// TODO 设置了工厂方法时, 用工厂方法实例化对应的bean并返回. 工厂方法有两种类型:
			//  1. 静态工厂方法: 不需要直接实例化工厂类即可使用工厂方法, 类似于静态类:
			//     A. XML配置方式:
			//        <bean id="car" class="factory.StaticCarFactory" factory-method="getCar">
			//            <constructor-arg value="Audio" />
			//        </bean>
			//        a. factoryBeanName: null, 静态工厂方法没有工厂
			//        b. factoryBean: null, 静态工厂方法没有工厂类
			//        c. factoryClass: 'class'属性, 指向的是静态工厂方法的全限定名, 即: 例子中的'factory.StaticCarFactory'
			//        d. factoryMethod: 'factory-method'属性, 指向静态工厂方法的名字, 即: 例子中的'getCar'
			//        e. explicitArgs: 'constructor-arg'标签所指定的, 用于调用工厂方法时使用的参数, 即: 例子中的'Audio'
			//     B. 注解配置方式, 解析过程在ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod(BeanMethod)方法中:
			//        package factory
			//        @Configuration
			//        Class CarConfiguration {
			//            @Bean
			//            public static getCar() {
			//            }
			//        }
			//        a. factoryBeanName: null, 静态工厂方法没有工厂
			//        b. factoryBean: null, 静态工厂方法没有工厂类
			//        c. factoryClass: 配置类的全限定名, 即: 例子中的'factory.CarConfiguration'
			//        d. factoryMethod: @Bean所标注的static方法 mark
			//        e. explicitArgs: mark
			//  2. 实例工厂: 实例化后才能使用工厂方法, 类似于普通类, 实例工厂没有'class'属性:
			//     A. XML配置方式:
			//        <bean id="carFactory" class="xxx.CarFactory">
			//        <bean id="car" factory-bean="carFactory" factory-method="getCar">
			//            <constructor-arg value="BMW"></constructor-arg>
			//        </bean>
			//        a. factoryBeanName: 'factory-bean'属性指定的实例工厂方法的名字, 调用工厂方法前需要实例化的类, 即: 例子中的'carFactory'
			//        b. factoryBean: 'factory-bean'属性所指定的bean实例, 即: 例子中的'<bean id="carFactory" class="xxx.CarFactory">'
			//        c. factoryClass: 'factory-bean'属性所指定的bean实例的Class对象, 即: 例子中的'xxx.CarFactory'
			//        d. factoryMethod: 'factory-method'属性: 指向实例工厂方法的名字, 即: 例子中的'getCar'
			//        e. explicitArgs: 'constructor-arg'标签所指定的, 用于调用工厂方法时使用的参数, 即: 例子中的'Audio'
			//     B. 注解配置方式, 解析过程在ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod(BeanMethod)方法中:
			//        package factory
			//        @Configuration
			//        Class CarConfiguration {
			//            @Bean
			//            public static getCar() {
			//            }
			//        }
			//        a. factoryBeanName: 注解方式的工厂类为@Bean所在的类的名字, 即: 例子中的'CarConfiguration'
			//        a. factoryBean: 注解方式的工厂类为@Bean所在的类, 即, 例子中的'CarConfiguration'
			//        b. factoryClass: 工厂类的Class对象, 即, 例子中的'CarConfiguration'
			//        c. factoryMethod: @Bean所标注的方法 mark
			//        d. explicitArgs: mark
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		// Shortcut when re-creating the same bean...
		// TODO 下面就是用其他方式实例化bean了. 这里设置了2个标志位. bean是否解析完成标志位, 对于已经解析过的bean, 提供一个快速断路操作
		boolean resolved = false;
		// TODO 是否需要自动注入. 这是由bean的构造参数是否解析完毕来决定的
		boolean autowireNecessary = false;
		if (args == null) {
			// TODO 没有为创建的bean指定属性参数时
			synchronized (mbd.constructorArgumentLock) {
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					// TODO 如果构造器或工厂方法已经解析完了, 即, 非第一次调用创建bean的方法时, 设置标识flag
					resolved = true;
					// TODO 根据构造器参数是否解析完毕来设置是否需要自动注入
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}
		if (resolved) {
			// TODO 对于非第一次调用创建bean的方法来说, 一切都是从缓存中取已经解析好的参数, 以及构造器或方法等来实例化bean
			if (autowireNecessary) {
				// TODO 需要自动注入时对构造器进行自动注入, 同时完成实例化. 这时是不需要构造器或工厂方法的的, mbd里已经有解析好的构造器或工厂方法了
				return autowireConstructor(beanName, mbd, null, null);
			}
			else {
				// TODO 不需要自动注入时, 用默认构造器直接实例化bean
				return instantiateBean(beanName, mbd);
			}
		}

		// Candidate constructors for autowiring?
		// TODO 第一次调用时会走到下面的逻辑, 即: 解析出用于实例化bean所需要的构造器. 首先拿出bean所有的构造器
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			// TODO bean有构造器, 或者自动注入模式是构造器注入, 或者构造器带参数, 或者传入了构造对象时要用的参数时, 对构造器进行自动注入并返回
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// Preferred constructors for default construction?
		// TODO 没有构造器时就选一个优先的构造器做为实例化bean的构造器, 有以下两个实现:
		//  1. GenericApplicationContext$ClassDerivedBeanDefinition: 针对Kotlin有实现. 其他情况返回的是bean引用的Class对象的全部public构造器
		//  2. RootBeanDefinition: 并不支持此操作, 直接返回null, 后面用无参构造器来进行实例化
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			// TODO 用支持自动装配的构造器进行实例化
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// No special handling: simply use no-arg constructor.
		// TODO 如果还是没找到, 最后就用无参构造器来实例化bean了
		return instantiateBean(beanName, mbd);
	}

	/**
	 * Obtain a bean instance from the given supplier.
	 * @param supplier the configured supplier
	 * @param beanName the corresponding bean name
	 * @return a BeanWrapper for the new instance
	 * @since 5.0
	 * @see #getObjectForBeanInstance
	 */
	// TODO 从Supplier中创建bean
	protected BeanWrapper obtainFromSupplier(Supplier<?> supplier, String beanName) {
		// TODO 通过Supplier函数得到实例
		Object instance = obtainInstanceFromSupplier(supplier, beanName);
		if (instance == null) {
			instance = new NullBean();
		}
		BeanWrapper bw = new BeanWrapperImpl(instance);
		initBeanWrapper(bw);
		return bw;
	}

	private Object obtainInstanceFromSupplier(Supplier<?> supplier, String beanName) {
		// TODO 拿出当前线程NamedThreadLoad的bean, 然后设置为新的bean
		String outerBean = this.currentlyCreatedBean.get();
		this.currentlyCreatedBean.set(beanName);
		try {
			if (supplier instanceof InstanceSupplier<?> instanceSupplier) {
				// TODO 通过Supplier函数得到实例
				return instanceSupplier.get(RegisteredBean.of(this, beanName));
			}
			if (supplier instanceof ThrowingSupplier<?> throwableSupplier) {
				return throwableSupplier.getWithException();
			}
			return supplier.get();
		}
		catch (Throwable ex) {
			if (ex instanceof BeansException beansException) {
				throw beansException;
			}
			throw new BeanCreationException(beanName,
					"Instantiation of supplied bean failed", ex);
		}
		finally {
			if (outerBean != null) {
				// TODO 如果当前线程NamedThreadLoad有值, 再设置一下
				this.currentlyCreatedBean.set(outerBean);
			}
			else {
				// TODO 否则从当前线程NamedThreadLoad中移除beanName
				this.currentlyCreatedBean.remove();
			}
		}
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 * @since 5.0
	 * @see #obtainFromSupplier
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		if (currentlyCreatedBean != null) {
			// TODO 注册依赖的bean
			registerDependentBean(beanName, currentlyCreatedBean);
		}

		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	@Nullable
	// TODO 用SmartInstantiationAwareBeanPostProcessor后处理器来确定要实例化bean时所需要的构造器
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {

		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			// TODO 在有明确了的bean的类型, 并且容器注册过InstantiationAwareBeanPostProcessor类型的初始化后处理器时, 开始类型预测.
			//  首先看一下要匹配的类型是否只有一个, 且是FactoryBean类型
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				// TODO 遍历所有的SmartInstantiationAwareBeanPostProcessor类型后处理器来确定构造器, 以下后处理器实现了此方法:
				//  1. SmartInstantiationAwareBeanPostProcessor接口: 提供了默认方法, 直接返回null;
				//  2. AbstractAutoProxyCreator抽象类: 直接返回null. 实际上Java 8后可以移除此方法了;
				//  3. InstantiationAwareBeanPostProcessorAdapter抽象类: 同上;
				//  4. AutowiredAnnotationBeanPostProcessor类: InstantiationAwareBeanPostProcessorAdapter的实现类, 用来
				//     确定要实例化的bean有哪些满足要求的构造器. 这里会处理@Lookup注解. 还会处理@Autowire, @Inject这些用来
				//     自动注入的注解. 这2个注解的'required'属性默认都为true, 只是@Inject没有设置此属性的地方. Spring只允许
				//     一个类中有一个构造器的'required'属性为true(@Autowire需要明确设置'required = false'), 否则会抛出异常.
				Constructor<?>[] ctors = bp.determineCandidateConstructors(beanClass, beanName);
				if (ctors != null) {
					// TODO 这也是个断路操作, 只要有一个后处理器找到了候选构造器, 直接返回
					return ctors;
				}
			}
		}
		return null;
	}

	/**
	 * Instantiate the given bean using its default constructor.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return a BeanWrapper for the new instance
	 */
	// TODO 用默认构造器实例化bean
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			// TODO 根据容器指定的实例化策略来实例化bean, 默认策略为SimpleInstantiationStrategy. CGLIB增强过的bean会使用
			//  CglibSubclassInstantiationStrategy策略来进行实例化
			Object beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			// TODO 将实例化后的bean包装为一个BeanWrapper, 初始化后返回
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			initBeanWrapper(bw);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * @param beanName the name of the bean 要创建实例的bean名
	 * @param mbd the bean definition for the bean 要创建实例的bean的mbd
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (implying the use of constructor argument values from bean definition) 为bean设置的参数值, 只有构造方法
	 * 	 *             和工厂方法可以用此参数来给取得的bean赋值, 同时bean的scope也必需是prototype类型
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	// TODO 设置了工厂方法时, 用工厂方法实例化对应的bean并返回. 工厂方法有两种类型:
	//  1. 静态工厂方法: 不需要直接实例化工厂类即可使用工厂方法, 类似于静态类:
	//     A. XML配置方式:
	//        <bean id="car" class="factory.StaticCarFactory" factory-method="getCar">
	//            <constructor-arg value="Audio" />
	//        </bean>
	//        a. factoryBeanName: null, 静态工厂方法没有工厂
	//        b. factoryBean: null, 静态工厂方法没有工厂类
	//        c. factoryClass: 'class'属性, 指向的是静态工厂方法的全限定名, 即: 例子中的'factory.StaticCarFactory'
	//        d. factoryMethod: 'factory-method'属性, 指向静态工厂方法的名字, 即: 例子中的'getCar'
	//        e. explicitArgs: 'constructor-arg'标签所指定的, 用于调用工厂方法时使用的参数, 即: 例子中的'Audio'
	//     B. 注解配置方式, 解析过程在ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod(BeanMethod)方法中:
	//        package factory
	//        @Configuration
	//        Class CarConfiguration {
	//            @Bean
	//            public static getCar() {
	//            }
	//        }
	//        a. factoryBeanName: null, 静态工厂方法没有工厂
	//        b. factoryBean: null, 静态工厂方法没有工厂类
	//        c. factoryClass: 配置类的全限定名, 即: 例子中的'factory.CarConfiguration'
	//        d. factoryMethod: @Bean所标注的static方法 mark
	//        e. explicitArgs: mark
	//  2. 实例工厂: 实例化后才能使用工厂方法, 类似于普通类, 实例工厂没有'class'属性:
	//     A. XML配置方式:
	//        <bean id="carFactory" class="xxx.CarFactory">
	//        <bean id="car" factory-bean="carFactory" factory-method="getCar">
	//            <constructor-arg value="BMW"></constructor-arg>
	//        </bean>
	//        a. factoryBeanName: 'factory-bean'属性指定的实例工厂方法的名字, 调用工厂方法前需要实例化的类, 即: 例子中的'carFactory'
	//        b. factoryBean: 'factory-bean'属性所指定的bean实例, 即: 例子中的'<bean id="carFactory" class="xxx.CarFactory">'
	//        c. factoryClass: 'factory-bean'属性所指定的bean实例的Class对象, 即: 例子中的'xxx.CarFactory'
	//        d. factoryMethod: 'factory-method'属性: 指向实例工厂方法的名字, 即: 例子中的'getCar'
	//        e. explicitArgs: 'constructor-arg'标签所指定的, 用于调用工厂方法时使用的参数, 即: 例子中的'Audio'
	//     B. 注解配置方式, 解析过程在ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod(BeanMethod)方法中:
	//        package factory
	//        @Configuration
	//        Class CarConfiguration {
	//            @Bean
	//            public static getCar() {
	//            }
	//        }
	//        a. factoryBeanName: 注解方式的工厂类为@Bean所在的类的名字, 即: 例子中的'CarConfiguration'
	//        a. factoryBean: 注解方式的工厂类为@Bean所在的类, 即, 例子中的'CarConfiguration'
	//        b. factoryClass: 工厂类的Class对象, 即, 例子中的'CarConfiguration'
	//        c. factoryMethod: @Bean所标注的方法 mark
	//        d. explicitArgs: mark
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		// TODO ConstructorResolver通过工厂方法来实例化一个bean
		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean 要实例化的bean
	 * @param mbd the bean definition for the bean 要实例化的bean的mbd
	 * @param ctors the chosen candidate constructors 候选构造器
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (implying the use of constructor argument values from bean definition) 实例化bean时, 指定的参数
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {

		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * Populate the bean instance in the given BeanWrapper with the property values
	 * from the bean definition.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param bw the BeanWrapper with bean instance
	 */
	@SuppressWarnings("deprecation")  // for postProcessPropertyValues
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		if (bw == null) {
			if (mbd.hasPropertyValues()) {
				// TODO 如果没有bean包装实例, 但mbd有bean的属性值时, 会抛出'Cannot apply property values to null instance'的
				//  BeanCreationException异常
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			else {
				// Skip property population phase for null instance.
				// TODO 同时mbd也没有属性值时, 直接跳过就好
				return;
			}
		}

		// Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
		// state of the bean before properties are set. This can be used, for example,
		// to support styles of field injection.
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				if (!bp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
					// TODO 对于容器创建的bean来说, 如果容器中任何一个InstantiationAwareBeanPostProcessor类型的后处理器表示
					//  不需要实例化后执行其他操作, 就无需要进行后续的属性设值(这是个断路操作)
					return;
				}
			}
		}
		// TODO 下面就是开始进行属性填充了, 先取得所有解析好的属性值
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);
		// TODO 取得自动装配的模式, 用于后续判断
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
			// Add property values based on autowire by name if applicable.
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				// TODO 开始按名字进行自动装配
				autowireByName(beanName, mbd, bw, newPvs);
			}
			// Add property values based on autowire by type if applicable.
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				// TODO 开始按类型进行自动装配
				autowireByType(beanName, mbd, bw, newPvs);
			}
			pvs = newPvs;
		}
		// TODO 判断容器是否注册了InstantiationAwareBeanPostProcessors类型的后处理器
		boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
		// TODO 当前bean是否需要依赖检查. bean的默认值是DEPENDENCY_CHECK_NONE, 表示不需要依赖检查. 一共有4种模式:
		//  1. DEPENDENCY_CHECK_NONE = 0: 不需要依赖检查
		//  2. DEPENDENCY_CHECK_OBJECTS = 1: 检查对象引用
		//  3. DEPENDENCY_CHECK_SIMPLE = 2: 检查简单属性
		//  4. DEPENDENCY_CHECK_ALL = 3: 同时检查对象引用和简单属性
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);

		if (hasInstAwareBpps) {
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// TODO 遍历所有InstantiationAwareBeanPostProcessor类型的后处理器, 来对属性进行处理:
				//  1. InstantiationAwareBeanPostProcessor: 接口提供了默认方法, 永远返回null;
				//  2. InstantiationAwareBeanPostProcessorAdapter: InstantiationAwareBeanPostProcessor接口的子类. 也是
				//     什么也没做, 直接返回null. 留着应该是为了兼容以前的版本. Java 8后可以去掉了;
				//  3. PersistenceAnnotationBeanPostProcessor: 用于JAP持久化相关功能. 在AnnotationConfigApplicationContext
				//     容器初始化时, 由AnnotatedBeanDefinitionReader创建. 会把bean里所有标有@PersistenceContext和@PersistenceUnit
				//     注解的字段以及方法全都找出来, 生成包含PersistenceElement的注入点元数据后, 根据注入点元数据信息进行注入操作
				//  4. AbstractAutoProxyCreator: 用于AOP代理创建. 其并没有对属性进行任何操作, 直接返回属性
				//  5. AutowiredAnnotationBeanPostProcessor: 用于处理自动装配注解的后处理器. 在AnnotationConfigApplicationContext
				//     容器初始化时由AnnotatedBeanDefinitionReader创建. 会把bean里所有标有@Autowire, @Value, @Inject注解的
				//     字段以及方法全都找出来, 生成包含有AutowiredFieldElement和AutowiredMethodElement的注入点元数据. 根据
				//     注入点元数据信息进行注入操作
				//  6. CommonAnnotationBeanPostProcessor: 用来处理通用资源的后处理器. AnnotationConfigApplicationContext
				//     容器初始化时, 由AnnotatedBeanDefinitionReader创建. 会把bean里所有标有@Autowire, @Value, @Inject注解
				//     的字段以及方法全都找出来, 生成包含有AutowiredFieldElement和AutowiredMethodElement的注入点元数据. 根据
				//     注入点元数据信息进行注入操作
				//  7. ConfigurationClassPostProcessor$ImportAwareBeanPostProcessor: 用来处理由CGLIB增强的@Configuration
				//     配置类. 为增强类设置了相同的容器
				//  8. ScriptFactoryPostProcessor: 不做任何处理, 直接返回属性
				PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
				if (pvsToUse == null) {
					return;
				}
				pvs = pvsToUse;
			}
		}
		if (needsDepCheck) {
			PropertyDescriptor[] filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}

		if (pvs != null) {
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally. 需要填充属性的bean名(当前正在创建的bean的名字)
	 * @param mbd bean definition to update through autowiring 属性填充属性的bean的mbd
	 * @param bw the BeanWrapper from which we can obtain information about the bean 包装好的, 用来返回的bw
	 * @param pvs the PropertyValues to register wired objects with 属性值
	 */
	// TODO 这里其实是按bean名进行自动装配
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			if (containsBean(propertyName)) {
				// TODO 如果容器中有属性所对应的bean, 取得对应的bean
				Object bean = getBean(propertyName);
				// TODO 设置到
				pvs.add(propertyName, bean);
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd the merged bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}

		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			try {
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is a unsatisfied, non-simple property.
				if (Object.class != pd.getPropertyType()) {
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					if (autowiredArgument != null) {
						pvs.add(propertyName, autowiredArgument);
					}
					for (String autowiredBeanName : autowiredBeanNames) {
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isTraceEnabled()) {
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					autowiredBeanNames.clear();
				}
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		Set<String> result = new TreeSet<>();
		PropertyValues pvs = mbd.getPropertyValues();
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				result.add(pd.getName());
			}
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			if (cache) {
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				if (existing != null) {
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		pds.removeIf(this::isExcludedFromDependencyCheck);
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition the bean was created with
	 * @param pds the relevant property descriptors for the target bean
	 * @param pvs the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		int dependencyCheck = mbd.getDependencyCheck();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * Apply the given property values, resolving any runtime references
	 * to other beans in this bean factory. Must use deep copy, so we
	 * don't permanently modify this property.
	 * @param beanName the bean name passed for better exception information
	 * @param mbd the merged bean definition
	 * @param bw the BeanWrapper wrapping the target object
	 * @param pvs the new property values
	 */
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		if (pvs.isEmpty()) {
			return;
		}

		MutablePropertyValues mpvs = null;
		List<PropertyValue> original;

		if (pvs instanceof MutablePropertyValues) {
			mpvs = (MutablePropertyValues) pvs;
			if (mpvs.isConverted()) {
				// Shortcut: use the pre-converted values as-is.
				try {
					bw.setPropertyValues(mpvs);
					return;
				}
				catch (BeansException ex) {
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			original = mpvs.getPropertyValueList();
		}
		else {
			original = Arrays.asList(pvs.getPropertyValues());
		}

		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// Create a deep copy, resolving any references for values.
		List<PropertyValue> deepCopy = new ArrayList<>(original.size());
		boolean resolveNecessary = false;
		for (PropertyValue pv : original) {
			if (pv.isConverted()) {
				deepCopy.add(pv);
			}
			else {
				String propertyName = pv.getName();
				Object originalValue = pv.getValue();
				if (originalValue == AutowiredPropertyMarker.INSTANCE) {
					Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
					if (writeMethod == null) {
						throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
					}
					originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
				}
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				Object convertedValue = resolvedValue;
				boolean convertible = bw.isWritableProperty(propertyName) &&
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				if (convertible) {
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				// Possibly store converted value in merged bean definition,
				// in order to avoid re-conversion for every created bean instance.
				if (resolvedValue == originalValue) {
					if (convertible) {
						pv.setConvertedValue(convertedValue);
					}
					deepCopy.add(pv);
				}
				else if (convertible && originalValue instanceof TypedStringValue &&
						!((TypedStringValue) originalValue).isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					pv.setConvertedValue(convertedValue);
					deepCopy.add(pv);
				}
				else {
					resolveNecessary = true;
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		if (mpvs != null && !resolveNecessary) {
			mpvs.setConverted();
		}

		// Set our (possibly massaged) deep copy.
		try {
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}
	}

	/**
	 * Convert the given value for the specified target property.
	 */
	@Nullable
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

		if (converter instanceof BeanWrapperImpl) {
			return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
		}
		else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}


	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
		// TODO 调用 BeanNameAware、BeanClassLoaderAware 或 BeanFactoryAware 接口, 就调用接口方法对bean实例进行处理
		invokeAwareMethods(beanName, bean);

		// TODO 之所以叫 wrappedBean 是因为后面后处理器可能会对 bean 实例进行加工包装
		Object wrappedBean = bean;
		if (mbd == null || !mbd.isSynthetic()) {
			// TODO 调用所有后处理的 postProcessBeforeInitialization 方法在bean初始化前做一些处理
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}

		try {
			// TODO 执行 init-method, 如果bean实现了 InitializingBean 接口，还会调用afterPropertiesSet()方法做一些特殊处理
			invokeInitMethods(beanName, wrappedBean, mbd);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null),
					beanName, "Invocation of init method failed", ex);
		}
		if (mbd == null || !mbd.isSynthetic()) {
			// TODO 调用所有后处理的 postProcessAfterInitialization 方法在bean初始化前做一些处理
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}

		return wrappedBean;
	}

	private void invokeAwareMethods(String beanName, Object bean) {
		if (bean instanceof Aware) {
			if (bean instanceof BeanNameAware) {
				((BeanNameAware) bean).setBeanName(beanName);
			}
			if (bean instanceof BeanClassLoaderAware) {
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
				}
			}
			if (bean instanceof BeanFactoryAware) {
				((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the merged bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
			throws Throwable {

		boolean isInitializingBean = (bean instanceof InitializingBean);
		if (isInitializingBean && (mbd == null || !mbd.hasAnyExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			// TODO 执行 InitializingBean 接口的 afterPropertiesSet 方法在 property 加载后由实现类做了一处理
			((InitializingBean) bean).afterPropertiesSet();
		}

		if (mbd != null && bean.getClass() != NullBean.class) {
			String[] initMethodNames = mbd.getInitMethodNames();
			if (initMethodNames != null) {
				for (String initMethodName : initMethodNames) {
					if (StringUtils.hasLength(initMethodName) &&
							!(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
							!mbd.hasAnyExternallyManagedInitMethod(initMethodName)) {
						// TODO 如果bean设置了 init-method 方法, 并且没实现 InitializingBean 接口或 init-method 方法与InitializingBean
						//  接口的 afterPropertiesSet 方法同名, 同时 init-method 方法没注册过时，执行 init-method 定义的方法
						invokeCustomInitMethod(beanName, bean, mbd, initMethodName);
					}
				}
			}
		}
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd, String initMethodName)
			throws Throwable {

		// TODO 根据 init-method 名通过反射找到对应的方法, 私有方法通过 BeanUtils 取得, 公有方法通过 ClassUtils 获得
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

		if (initMethod == null) {
			// TODO 没有找到要执行的方法时, 就要看是不是有强制使用 init-method 的要求, 有就抛个异常, 没有就记个log
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		// TODO 下面就是通过反射来执行 init-method 指定的方法了
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod, bean.getClass());

		try {
			ReflectionUtils.makeAccessible(methodToInvoke);
			methodToInvoke.invoke(bean);
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}


	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		// TODO 挨个调用BeanPostProcessor.postProcessAfterInitialization()对从工厂类得到的bean进行处理并返回, 这是初始化后的动作
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}


	/**
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}


	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
			if (isFactoryBeanMethod(method)) {
				// TODO 当处理的方法返回的是工厂类, 且与finder记录的工厂方法相同时, 解析出方法的返回类型, 及其泛型
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				if (this.result == ResolvableType.NONE) {
					// TODO result的初始值是ResolvableType.NONE, 所以第一次进来时会被替换为方法返回类型的泛型ResolvableType
					this.result = candidate;
				}
				else {
					// TODO 后面再进来时就是解析泛型了
					Class<?> resolvedResult = this.result.resolve();
					// TODO 然后再确定一下返回类型和其泛型类型的共同父类型
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						// TODO 两个类型不同时, 用共同的父类型替代
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			// TODO 判断方法的返回类型是否为工厂类, 并且是创建FactoryBeanMethodTypeFinder所指定的那个方法
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			Class<?> resolved = this.result.resolve();
			boolean foundResult = resolved != null && resolved != Object.class;
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}
