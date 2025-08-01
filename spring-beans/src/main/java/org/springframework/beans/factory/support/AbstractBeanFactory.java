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

package org.springframework.beans.factory.support;

import java.beans.PropertyEditor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.PropertyEditorRegistrySupport;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanIsAbstractException;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.Scope;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.log.LogMessage;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Abstract base class for {@link org.springframework.beans.factory.BeanFactory}
 * implementations, providing the full capabilities of the
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI.
 * Does <i>not</i> assume a listable bean factory: can therefore also be used
 * as base class for bean factory implementations which obtain bean definitions
 * from some backend resource (where bean definition access is an expensive operation).
 *
 * <p>This class provides a singleton cache (through its base class
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype determination, {@link org.springframework.beans.factory.FactoryBean}
 * handling, aliases, bean definition merging for child bean definitions,
 * and bean destruction ({@link org.springframework.beans.factory.DisposableBean}
 * interface, custom destroy methods). Furthermore, it can manage a bean factory
 * hierarchy (delegating to the parent in case of an unknown bean), through implementing
 * the {@link org.springframework.beans.factory.HierarchicalBeanFactory} interface.
 *
 * <p>The main template methods to be implemented by subclasses are
 * {@link #getBeanDefinition} and {@link #createBean}, retrieving a bean definition
 * for a given bean name and creating a bean instance for a given bean definition,
 * respectively. Default implementations of those operations can be found in
 * {@link DefaultListableBeanFactory} and {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 15 April 2001
 * @see #getBeanDefinition
 * @see #createBean
 * @see AbstractAutowireCapableBeanFactory#createBean
 * @see DefaultListableBeanFactory#getBeanDefinition
 */
public abstract class AbstractBeanFactory extends FactoryBeanRegistrySupport implements ConfigurableBeanFactory {

	/** Parent bean factory, for bean inheritance support. */
	private @Nullable BeanFactory parentBeanFactory;

	/** ClassLoader to resolve bean class names with, if necessary. */
	private @Nullable ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	/** ClassLoader to temporarily resolve bean class names with, if necessary. */
	private @Nullable ClassLoader tempClassLoader;

	/** Whether to cache bean metadata or rather reobtain it for every access. */
	private boolean cacheBeanMetadata = true;

	/** Resolution strategy for expressions in bean definition values. */
	private @Nullable BeanExpressionResolver beanExpressionResolver;

	/** Spring ConversionService to use instead of PropertyEditors. */
	private @Nullable ConversionService conversionService;

	/** Default PropertyEditorRegistrars to apply to the beans of this factory. */
	private final Set<PropertyEditorRegistrar> defaultEditorRegistrars = new LinkedHashSet<>(4);

	/** Custom PropertyEditorRegistrars to apply to the beans of this factory. */
	private final Set<PropertyEditorRegistrar> propertyEditorRegistrars = new LinkedHashSet<>(4);

	/** Custom PropertyEditors to apply to the beans of this factory. */
	private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors = new HashMap<>(4);

	/** A custom TypeConverter to use, overriding the default PropertyEditor mechanism. */
	private @Nullable TypeConverter typeConverter;

	/** String resolvers to apply, for example, to annotation attribute values. */
	private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

	/** BeanPostProcessors to apply. */
	private final List<BeanPostProcessor> beanPostProcessors = new BeanPostProcessorCacheAwareList();

	/** Cache of pre-filtered post-processors. */
	private @Nullable BeanPostProcessorCache beanPostProcessorCache;

	/** Map from scope identifier String to corresponding Scope. */
	private final Map<String, Scope> scopes = new LinkedHashMap<>(8);

	/** Application startup metrics. */
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;

	/** Map from bean name to merged RootBeanDefinition. */
	private final Map<String, RootBeanDefinition> mergedBeanDefinitions = new ConcurrentHashMap<>(256);

	/** Names of beans that have already been created at least once. */
	// TODO 已经创建过的bean的缓存
	private final Set<String> alreadyCreated = ConcurrentHashMap.newKeySet(256);

	/** Names of beans that are currently in creation. */
	private final ThreadLocal<Object> prototypesCurrentlyInCreation =
			new NamedThreadLocal<>("Prototype beans currently in creation");


	/**
	 * Create a new AbstractBeanFactory.
	 */
	public AbstractBeanFactory() {
	}

	/**
	 * Create a new AbstractBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 * @see #getBean
	 */
	public AbstractBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this.parentBeanFactory = parentBeanFactory;
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	// TODO 按名称获取 Bean
	public Object getBean(String name) throws BeansException {
		// TODO 默认不需要类型, 参数, 以及类型检查
		return doGetBean(name, null, null, false);
	}

	@Override
	// TODO 按名称获取指定类型的 Bean
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		// TODO 默认不需要参数, 以及类型检查
		return doGetBean(name, requiredType, null, false);
	}

	@Override
	// TODO 按名称获取指定的 bean, 设置了获取 bean 时要用的参数
	public Object getBean(String name, @Nullable Object @Nullable ... args) throws BeansException {
		// TODO 按名称获取 bean 的同时, 使用 args 参数对 bean 的属性进行赋值(构造方法和工厂方法属性), 此时 bean 必须是 prototype 类型
		return doGetBean(name, null, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve 要取得的bean的名字
	 * @param requiredType the required type of the bean to retrieve 要取得的bean的类型
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one) 为bean设置的参数值, 仅在创建Bean
	 *             时有值
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	public <T> T getBean(String name, @Nullable Class<T> requiredType, @Nullable Object @Nullable ... args)
			throws BeansException {

		return doGetBean(name, requiredType, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve 要获取的bean的名字
	 * @param requiredType the required type of the bean to retrieve 获取的bean的类型
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one) 为bean设置的参数值, 只有构造方法
	 *             和工厂方法可以用此参数来给取得的bean赋值, 同时bean的scope也必需是prototype类型
	 * @param typeCheckOnly whether the instance is obtained for a type check,
	 * not for actual use
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	@SuppressWarnings("unchecked")
	protected <T> T doGetBean(
			String name, @Nullable Class<T> requiredType, @Nullable Object @Nullable [] args, boolean typeCheckOnly)
			throws BeansException {
		// TODO transformedBeanName() 用来规范化要取得的 bean 的名字, 此方法做了两件事:
		//  1. 去掉'&': Spring 中工厂类是以 '&' 开头的(表示为一个引用, 而非 bean ). 使用 getBean("&xxx") 方法时, 得到的会是 FactoryBean,
		//             而使用 getBean("xxx") 方法得到的是 bean 本身, 或者 FactoryBean 中的对象(最终返回的是 beanFactory.getObject() ).
		//             这里去掉 '&' 后, 后面再取的 bean 就是 FactoryBean 中的对象了
		//  2. 取得最终名: bean 的映射可能会出现别名嵌套, 即 bean A 映射成了别名 B, B 又被映射成了 C, 即: A -> B -> C 的情况
		//                如果传入的名字是 B 或 C, 最终得到的名字会是最初的名字 A
		String beanName = transformedBeanName(name);
		Object beanInstance;

		// Eagerly check singleton cache for manually registered singletons.
		// TODO 按以下顺序尝试从缓存中取得对应的单例实例:
		//  1. singletonObjects: 第一级缓存, 存放的是已经创建成功的单例 bean, bean 名 -> bean 实例
		//  2. earlySingletonObjects: 第二级缓存, 存放的是提前暴露的单例 bean, 这个 bean 还没有实例化结束, 是不完整的(比如还没有设置属性), bean 名 -> bean 实例
		//  3. singletonFactories: 第三级缓存, 存放的是创建 bean 的工厂类. 创建 bean 时, 先把自己的工厂类放进缓存, 然后才开始创建, bean 名 -> ObjectFactory
		//     从这里找到后, 会从工厂取出 bean 实例放入第二缓存, 然后再将工厂类从第三级缓存中清除
		Object sharedInstance = getSingleton(beanName);
		if (sharedInstance != null && args == null) {
			// TODO args 只有在 bean 是 prototype 类型, 同时用构造方式或工厂方法取得 bean 时才会有值, 所以这里表示的是缓存命中, 且为单例的情况?? mark
			if (logger.isTraceEnabled()) {
				if (isSingletonCurrentlyInCreation(beanName)) {
					logger.trace("Returning eagerly cached instance of singleton bean '" + beanName +
							"' that is not fully initialized yet - a consequence of a circular reference");
				}
				else {
					logger.trace("Returning cached instance of singleton bean '" + beanName + "'");
				}
			}
			// TODO 从得到的单例实例中取得 bean 对象, getObjectForBeanInstance() 方法的主要是逻辑是:
			//  1. 如果要取得的 bean 的名字是 '&' 开头的工厂类本身, 即, getBean("&xxx"), 则前面得到的 sharedInstance 实例就是工厂类本身, 直接返回即可;
			//  2. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 则直接返回前面得到的 sharedInstance 实例;
			//  3. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 但得到的实例实现了工厂接口 FactoryBean 时, 先从缓存取得 bean 实例,
			//     如果缓存中没有, 则调用工厂类实例的 getObject() 方法取得 bean, 然后再根据 bean 是否由程序本身创建来决定是否进行后处理加工
			//     (由程序本身创建 bean, 会用后处理器进行处理)
			beanInstance = getObjectForBeanInstance(sharedInstance, requiredType, name, beanName, null);
		}

		else {
			// TODO 缓存中不存时, 就要开始从父容器中查找:
			// Fail if we're already creating this bean instance:
			// We're assumably within a circular reference.
			if (isPrototypeCurrentlyInCreation(beanName)) {
				// TODO spring 无法处理 prototype 的 bean 的循环依赖, 如果创建过 beanName 的 prototype 类型的 bean,
				//  就表示出现了循环引用问题, 需要抛出异常
				throw new BeanCurrentlyInCreationException(beanName);
			}

			// Check if bean definition exists in this factory.
			// TODO 先从当前容器里看看要得到的 BeanDefinition 是否存在
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
				// Not found -> check parent.
				// TODO 当前容器没有要取得到 bean 时, 从父容器加载 bean. 下面几种容器实现了 getBean():
				//  1. AbstractBeanFactory: 实现的是通过名字查找 bean 的一系列方法:
				//                          getBean(String),
				//                          getBean(String, Class<T>)
				//                          getBean(String, Object...)
				//                          getBean(String, Class<T>, Object...)
				//  2. AbstractApplicationContext: 通过名字查找 bean 的 getBean() 最终调用的还是 AbstractBeanFactory#getBean(),
				//                                 增加了通过类型查找 bean 的功能
				//  3. SimpleJndiBeanFactory: 通过 jndi 查找 bean
				//  4. StaticListableBeanFactory: 从缓存中查找 bean
				//  先得到一个真实名字(对于工厂类来说 originalBeanName() 方法会在得到其真实名后, 为其添加'&', 表示其为一个解引用, 而非bean)
				String nameToLookup = originalBeanName(name);
				if (parentBeanFactory instanceof AbstractBeanFactory abf) {
					// TODO 如果父容器是 AbstractBeanFactory 类型, 即: AbstractAutowireCapableBeanFactory,
					//  DefaultListableBeanFactory, 或 XmlBeanFactory 时, 调用父容器的 doGetBean() 方法取得bean
					return abf.doGetBean(nameToLookup, requiredType, args, typeCheckOnly);
				}
				// TODO 后面的情况就是父容器不是 AbstractBeanFactory 的情况了, 会根据容器类型使用对应的 getBean()实现
				else if (args != null) {
					// Delegation to parent with explicit args.
					// TODO 取得 bean 时, 可以同时为 bean 属性设置值, 用于工厂方法, 或构造函数上. 对于这种情况, 需要将要设置的参数传递
					//  给父容器, 所以调用父容器对应的 getBean(String, Object... args) 方法
					return (T) parentBeanFactory.getBean(nameToLookup, args);
				}
				else if (requiredType != null) {
					// No args -> delegate to standard getBean method.
					// TODO 指定获取类型时, 调用父容器对应的 getBean(String, Class<T>) 方法
					return parentBeanFactory.getBean(nameToLookup, requiredType);
				}
				else {
					// TODO 普通的直接调用父容器的 getBean() 取得bean对象
					return (T) parentBeanFactory.getBean(nameToLookup);
				}
			}

			if (!typeCheckOnly) {
				// TODO 把 bean 标识为已创建, 放 alreadyCreated 缓存中
				markBeanAsCreated(beanName);
			}

			StartupStep beanCreation = this.applicationStartup.start("spring.beans.instantiate")
					.tag("beanName", name);
			try {
				if (requiredType != null) {
					beanCreation.tag("beanType", requiredType::toString);
				}
				// TODO 为要取得的 bean 生成 RootBeanDefinition 类型的 mbd (如果有双亲 BeanDefinition, 会合并双亲 BeanDefinition 的属性)
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				// TODO 确保合并后的 BeanDefinition 不能是抽象类, 这里做一下查检, 如果是抽象类则抛出 BeanIsAbstractException 异常
				checkMergedBeanDefinition(mbd, beanName, args);

				// Guarantee initialization of beans that the current bean depends on.
				// TODO 取得合并过双亲属性的 BeanDefinition 所依赖的所有 bean, 用于属性的依赖注入. 这里的目的是要保证当前 bean 所依赖的其他 bean
				//  要先于当前 bean 完成实例化. @DependsOn 注解可以控制 Bean 的初始化顺序, 要保证这些 bean 先完成实例化
				String[] dependsOn = mbd.getDependsOn();
				if (dependsOn != null) {
					for (String dep : dependsOn) {
						// TODO 然后检查一下循环依赖问题, 看一下要取得的这个 bean 是否出现在自己要依赖的 bean 中, 如果是则抛出异常
						if (isDependent(beanName, dep)) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
						}
						// TODO 没有循环依赖时, 注册一下 bean 与其依赖 bean 的关系
						//  1. dependentBeanMap: 注册了, 依赖 bean -> 当前 bean, 即依赖的 bean 依赖了当前 bean
						//  2. dependenciesForBeanMap: 注册了, 当前 bean -> 依赖的 bean, 即当前 bean 被依赖 bean 所依赖
						registerDependentBean(dep, beanName);
						try {
							// TODO 然后开始初始化依赖的 bean, 只有这些依赖的 bean 都初始化完成后, 才能继续当前 bean 的初始化
							getBean(dep);
						}
						catch (NoSuchBeanDefinitionException ex) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"'" + beanName + "' depends on missing bean '" + dep + "'", ex);
						}
						catch (BeanCreationException ex) {
							if (requiredType != null) {
								// Wrap exception with current bean metadata but only if specifically
								// requested (indicated by required type), not for depends-on cascades.
								throw new BeanCreationException(mbd.getResourceDescription(), beanName,
										"Failed to initialize dependency '" + ex.getBeanName() + "' of " +
												requiredType.getSimpleName() + " bean '" + beanName + "': " +
												ex.getMessage(), ex);
							}
							throw ex;
						}
					}
				}

				// Create bean instance.
				if (mbd.isSingleton()) {
					// TODO 创建单例 bean 实例, 这里创建的是原始的 bean 实例
					//  getSingleton(String, ObjectFactory<?>) 的第二个参数 ObjectFactory 是个函数接口, 提供了一个 getObject() 方法
					//  用来实现获取 bean 的主逻辑, 在这里传入的是 createBean() 方法, 最终会由此方法来为 bean 创建实例
					sharedInstance = getSingleton(beanName, () -> {
						try {
							// TODO 这里真正实现了创建单例 bean 实例的逻辑, 这里使用的是 AbstractBeanFactory 抽象工厂的子类
							//  AbstractAutowireCapableBeanFactory#createBean() 来创建原始bean实例
							return createBean(beanName, mbd, args);
						}
						catch (BeansException ex) {
							// Explicitly remove instance from singleton cache: It might have been put there
							// eagerly by the creation process, to allow for circular reference resolution.
							// Also remove any beans that received a temporary reference to the bean.
							destroySingleton(beanName);
							throw ex;
						}
					});
					// TODO 与上面相同, 从得到的单例实例中取得 bean 对象, 区别是在实例为工厂类时, 设置 mbd 也同为工厂类,
					//  getObjectForBeanInstance() 方法的主要是逻辑是:
					//  1. 如果要取得的 bean 的名字是 '&' 开头的工厂类本身, 即, getBean("&xxx"), 则前面得到的 sharedInstance 实例就是工厂类本身, 直接返回即可;
					//  2. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 则直接返回前面得到的 sharedInstance 实例;
					//  3. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 但得到的实例实现了工厂接口 FactoryBean 时, 先从缓存取得 bean 实例,
					//     如果缓存中没有, 则调用工厂类实例的 getObject() 方法取得 bean, 然后再根据 bean 是否由程序本身创建来决定是否进行后处理加工
					//     (由程序本身创建 bean , 会用后处理器进行处理)
					beanInstance = getObjectForBeanInstance(sharedInstance, requiredType, name, beanName, mbd);
				}

				else if (mbd.isPrototype()) {
					// TODO 创建原型bean实例
					// It's a prototype -> create a new instance.
					Object prototypeInstance = null;
					try {
						// TODO 实例创建开始前, 把要创建的bean实例注册到当前线程的 ThreadLocal 中
						beforePrototypeCreation(beanName);
						// TODO 然后创建一个原型 bean 实例
						prototypeInstance = createBean(beanName, mbd, args);
					}
					finally {
						// TODO 创建结束后, 从 ThreadLocal 中移除当前创建的 bean 实例
						afterPrototypeCreation(beanName);
					}
					// TODO 与上面相同, 从得到的原型实例中取得 bean 对象, 区别是在实例为工厂类时, 设置 mbd 也同为工厂类,
					//  getObjectForBeanInstance() 方法的主要是逻辑是:
					//  1. 如果要取得的 bean 的名字是 '&' 开头的工厂类本身, 即, getBean("&xxx"), 则前面得到的 sharedInstance 实例就是工厂类本身, 直接返回即可;
					//  2. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 则直接返回前面得到的 sharedInstance 实例;
					//  3. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 但得到的实例实现了工厂接口 FactoryBean 时, 先从缓存取得 bean 实例,
					//     如果缓存中没有, 则调用工厂类实例的 getObject() 方法取得 bean , 然后再根据 bean 是否由程序本身创建来决定是否进行后处理加工
					//     (由程序本身创建 bean, 会用后处理器进行处理)
					beanInstance = getObjectForBeanInstance(prototypeInstance, requiredType, name, beanName, mbd);
				}

				else {
					// TODO 创建其他类型的实例, 先取得 scope
					String scopeName = mbd.getScope();
					if (!StringUtils.hasLength(scopeName)) {
						throw new IllegalStateException("No scope name defined for bean '" + beanName + "'");
					}
					Scope scope = this.scopes.get(scopeName);
					if (scope == null) {
						throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
					}
					try {
						Object scopedInstance = scope.get(beanName, () -> {
							// TODO 实例创建开始前, 把要创建的 bean 实例注册到当前线程的 ThreadLocal 中
							beforePrototypeCreation(beanName);
							try {
								// TODO 创建实例
								return createBean(beanName, mbd, args);
							}
							finally {
								// TODO 创建结束后, 从 ThreadLocal 中移除当前创建的 bean 实例
								afterPrototypeCreation(beanName);
							}
						});
						// TODO 与上面相同, 从得到的 scope 实例中取得 bean 对象, 区别是在实例为工厂类时, 设置 mbd 也同为工厂类,
						//  getObjectForBeanInstance() 方法的主要是逻辑是:
						//  1. 如果要取得的 bean 的名字是'&'开头的工厂类本身, 即, getBean("&xxx"), 则前面得到的 sharedInstance 实例就是工厂类本身, 直接返回即可;
						//  2. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 则直接返回前面得到的 sharedInstance 实例;
						//  3. 如果要得到的 bean 的名字是普通 bean, 即, getBean("xxx"), 但得到的实例实现了工厂接口 FactoryBean 时, 先从缓存取得 bean 实例,
						//     如果缓存中没有, 则调用工厂类实例的 getObject() 方法取得 bean, 然后再根据 bean 是否由程序本身创建来决定是否进行后处理加工
						//     (由程序本身创建 bean, 会用后处理器进行处理)
						beanInstance = getObjectForBeanInstance(scopedInstance, requiredType, name, beanName, mbd);
					}
					catch (IllegalStateException ex) {
						throw new ScopeNotActiveException(beanName, scopeName, ex);
					}
				}
			}
			catch (BeansException ex) {
				beanCreation.tag("exception", ex.getClass().toString());
				beanCreation.tag("message", String.valueOf(ex.getMessage()));
				// TODO 出问题后把 bean 从缓存 alreadyCreated 中移除, 然后抛出异常
				cleanupAfterBeanCreationFailure(beanName);
				throw ex;
			}
			finally {
				beanCreation.end();
				if (!isCacheBeanMetadata()) {
					clearMergedBeanDefinition(beanName);
				}
			}
		}

		return adaptBeanInstance(name, beanInstance, requiredType);
	}

	@SuppressWarnings("unchecked")
	<T> T adaptBeanInstance(String name, Object bean, @Nullable Class<?> requiredType) {
		// Check if required type matches the type of the actual bean instance.
		if (requiredType != null && !requiredType.isInstance(bean)) {
			try {
				// TODO 指定了 bean 类型时, 将得到的 bean 转换成对应的类型并返回, 转换失败, 或类型不匹配时会抛出异常,
				Object convertedBean = getTypeConverter().convertIfNecessary(bean, requiredType);
				if (convertedBean == null) {
					throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
				}
				return (T) convertedBean;
			}
			catch (TypeMismatchException ex) {
				if (logger.isTraceEnabled()) {
					logger.trace("Failed to convert bean '" + name + "' to required type '" +
							ClassUtils.getQualifiedName(requiredType) + "'", ex);
				}
				throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
			}
		}
		// TODO 这里返回的是实例化后的 bean, 后面会进行初始化
		return (T) bean;
	}

	@Override
	// TODO 判断容器中是否包含指定 bean
	public boolean containsBean(String name) {
		String beanName = transformedBeanName(name);
		if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
			// TODO 如果存在于当前容器中(单例缓存, 或注册中心中), 工厂类, 或者非工厂引用都算存在
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
		}
		// Not found -> check parent.
		// TODO 当前容器没有, 则向上找父容器
		BeanFactory parentBeanFactory = getParentBeanFactory();
		return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
	}

	@Override
	// TODO 判断给定的bean是否为单例
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		// TODO 取得bean的最终使用名
		String beanName = transformedBeanName(name);
		// TODO 用名字取得容器中的原生单例bean对象
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				// TODO 如果得到的bean对象实例是工厂类(实现了FactoryBean接口)时, 返回值:
				//  true: 1. 传入的bean是工厂类(参数name以'&'开头);
				//        2. 或者取得的bean对象所表示的工厂类是单例的(本身不就是单例的了么??)
				return (BeanFactoryUtils.isFactoryDereference(name) || factoryBean.isSingleton());
			}
			else {
				// TODO bean对象实例不是工厂类时, 就看传入的bean名字是否以'&'开头的工厂类了(非工厂类都为单例):
				//  true: 参数name不以'&'开头, 表示不是工厂类
				//  false: 参数name以'&'开头, 表示是工厂类
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}

		// No singleton instance found -> check bean definition.
		// TODO 没有得到bean实例时, 就到父容器中去找, 看其是否为单例
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// TODO 有父容器, 且当前容器中没有要找的bean时, 就到看其在父容器中是否为单例
			return parentBeanFactory.isSingleton(originalBeanName(name));
		}
		// TODO 还没找到, 就用当前bean与双亲进行合并
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// In case of FactoryBean, return singleton status of created object if not a dereference.
		// TODO 判断合并后的mbd是否单例
		if (mbd.isSingleton()) {
			if (isFactoryBean(beanName, mbd)) {
				// TODO 是单例时, 且mbd是工厂类时
				if (BeanFactoryUtils.isFactoryDereference(name)) {
					// TODO 如果传入的名字是工厂类(以'&'开头), 就返回true
					return true;
				}
				// TODO name不是'&'开头的工厂类时, 从容器中取得bean最终名的工厂类('&'+bean最终名), 然后看其是否为单例
				FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
				return factoryBean.isSingleton();
			}
			else {
				// TODO mbd不是工厂类时, 就看传入的bean名字是否以'&'开头的工厂类了(非工厂类都为单例):
				//  true: 参数name不以'&'开头, 表示不是工厂类
				//  false: 参数name以'&'开头, 表示是工厂类
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isPrototype(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isPrototype()) {
			// In case of FactoryBean, return singleton status of created object if not a dereference.
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName, mbd));
		}

		// Singleton or scoped - not a prototype.
		// However, FactoryBean may still produce a prototype object...
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			return false;
		}
		if (isFactoryBean(beanName, mbd)) {
			FactoryBean<?> fb = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
			return ((fb instanceof SmartFactoryBean<?> smartFactoryBean && smartFactoryBean.isPrototype()) ||
					!fb.isSingleton());
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, typeToMatch, true);
	}

	/**
	 * Internal extended variant of {@link #isTypeMatch(String, ResolvableType)}
	 * to check whether the bean with the given name matches the specified type. Allow
	 * additional constraints to be applied to ensure that beans are not created early.
	 * @param name the name of the bean to query 要匹配的候选bean
	 * @param typeToMatch the type to match against (as a
	 * {@code ResolvableType}) 要匹配的类型
	 * @return {@code true} if the bean type matches, {@code false} if it
	 * doesn't match or cannot be determined yet
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @since 5.2
	 * @see #getBean
	 * @see #getType
	 */
	// TODO 判断给定的 bean 名是否与指定的类型相匹配
	protected boolean isTypeMatch(String name, ResolvableType typeToMatch, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {
		// TODO 取得 bean 的 id
		String beanName = transformedBeanName(name);
		// TODO 判断一下给定的bean 是否为工厂类('&'开头)
		boolean isFactoryDereference = BeanFactoryUtils.isFactoryDereference(name);

		// Check manually registered singletons.
		// TODO 根据最终名字取得容器中注册的原生的 bean 实例对象, 不支持提前暴露
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {

			// Determine target for FactoryBean match if necessary.
			// TODO 取到 bean 的实例对象时会根据其是否为工厂类执行不同的判断方法
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				// TODO bean 实例对象为工厂类时(实现了 FactoryBean 接口)
				if (!isFactoryDereference) {
					if (factoryBean instanceof SmartFactoryBean<?> smartFactoryBean &&
							smartFactoryBean.supportsType(typeToMatch.toClass())) {
						return true;
					}
					// TODO 对于传入的 bean 名不是以 '&' 开头的工厂类, 用 bean 实例表示的工厂类的类型做为判断条件
					Class<?> type = getTypeForFactoryBean(factoryBean);
					if (type == null) {
						return false;
					}
					if (typeToMatch.isAssignableFrom(type)) {
						return true;
					}
					else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
						RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
						ResolvableType targetType = mbd.targetType;
						if (targetType == null) {
							targetType = mbd.factoryMethodReturnType;
						}
						if (targetType == null) {
							return false;
						}
						Class<?> targetClass = targetType.resolve();
						if (targetClass != null && FactoryBean.class.isAssignableFrom(targetClass)) {
							Class<?> classToMatch = typeToMatch.resolve();
							if (classToMatch != null && !FactoryBean.class.isAssignableFrom(classToMatch) &&
									!classToMatch.isAssignableFrom(targetType.toClass())) {
								return typeToMatch.isAssignableFrom(targetType.getGeneric());
							}
						}
						else {
							// TODO 传入的本身就是一个工厂类时( name 参数以'&'开头), 实例本身即为工厂类的实例, 直接进行比较即可
							return typeToMatch.isAssignableFrom(targetType);
						}
					}
					return false;
				}
			}
			else if (isFactoryDereference) {
				return false;
			}

			// Actual matching against bean instance...
			// TODO bean实例对象不是工厂类, 且传入的bean名不是以'&'开头的工厂类时
			if (typeToMatch.isInstance(beanInstance)) {
				// Direct match for exposed instance?
				// TODO 直接比较指定的类型与bean实例对象, 看是否匹配
				return true;
			}
			else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
				// Generics potentially only match on the target class, not on the proxy...
				// TODO bean实例不匹配指定类型时, 如果要匹配的bean已经在容器中注册过时, 就进行泛型类型的匹配. 只对代理目标类
				//  进行泛型匹配, 不对代理类进行匹配操作
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				// TODO 取得mbd所代表的Class对象
				Class<?> targetType = mbd.getTargetType();
				if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance)) {
					// Check raw class match as well, making sure it's exposed on the proxy.
					// TODO mbd所代表的Class对象如果和bean实例不一样时, 需要比较mbd所代表的Class对象与指定类型的Class对象
					//  是否匹配了(一定要注意, 上面都是ResolvableType间的比较, 这里才是原生Class对象的比较)
					Class<?> classToMatch = typeToMatch.resolve();
					if (classToMatch != null && !classToMatch.isInstance(beanInstance)) {
						// TODO 如果指定的类型与bean的实例对象不匹配时, 返回false
						return false;
					}
					if (typeToMatch.isAssignableFrom(targetType)) {
						// TODO 指定的类型与mbd所代表的Class对象类型相同时, 返回true
						return true;
					}
				}
				// TODO 还是没有比较结果时, 就用mbd持有的bean的ResolvableType进行最终比较
				ResolvableType resolvableType = mbd.targetType;
				if (resolvableType == null) {
					// TODO mbd持有的bean没有ResolvableType属性值时, 其有可能是个工厂方法. 下面会尝试使用可能存在的工厂方法
					//  的返回类型进行比较
					resolvableType = mbd.factoryMethodReturnType;
				}
				// TODO 比较是否匹配
				return (resolvableType != null && typeToMatch.isAssignableFrom(resolvableType));
			}
			else {
				// TODO 其他情况(bean实例非工厂类, 但是传入的bean名是'&'开头的工厂类), 返回false
				return false;
			}
		}
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			// TODO bean是单例, 但并不在注册中心中时, 即空实例, 返回false
			return false;
		}

		// No singleton instance found -> check bean definition.
		// TODO 到这就表示没到找对应的单例实例, 这时需要到可能存在的父容器中查找
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// TODO 有父容器, 且bean不包含在当前容器中, 委托给父容器进行匹配验证
			return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
		}

		// Retrieve corresponding bean definition.
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		// TODO 取得bean的代理目标
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();

		// Set up the types that we want to match against
		// TODO 看一下待匹配的ResolvableType是否有解析过的type类型, 如果没有, 则默认使用FactoryBean(工厂类)
		Class<?> classToMatch = typeToMatch.resolve();
		if (classToMatch == null) {
			classToMatch = FactoryBean.class;
		}
		// TODO 转化成type数组, 为的是给非工厂类加上FactoryBean type??
		Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
				new Class<?>[] {classToMatch} : new Class<?>[] {FactoryBean.class, classToMatch});

		// Attempt to predict the bean type
		Class<?> predictedType = null;

		// We're looking for a regular reference, but we're a factory bean that has
		// a decorated bean definition. The target bean should be the same type
		// as FactoryBean would ultimately return.
		if (!isFactoryDereference && dbd != null && isFactoryBean(beanName, mbd)) {
			// TODO 要得到的bean不是一个以'&'开头的工厂类, 但bean本身是个工厂且, 且还是一个代理时, 下面就准备用代理目标类进行类型匹配验证
			// We should only attempt if the user explicitly set lazy-init to true
			// and we know the merged bean definition is for a factory bean.
			// TODO 判断bean是否为非懒加载, 或者支持工厂类初始化
			if (!mbd.isLazyInit() || allowFactoryBeanInit) {
				// TODO 不是懒加载, 或者支持工厂类初始化时, 取得代理目标类的mbd
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				// TODO 预测代理目标类tbd的类型:
				//  1. AbstractBeanFactory: 默认的实现
				//  2. AbstractAutowireCapableBeanFactory: 用于自动装配
				Class<?> targetType = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
				if (targetType != null && !FactoryBean.class.isAssignableFrom(targetType)) {
					// TODO 代理目标的类型不是工厂类时, 将其做为预测类型
					predictedType = targetType;
				}
			}
		}

		// If we couldn't use the target type, try regular prediction.
		if (predictedType == null) {
			// TODO 代理目标无法预测出类型时, 表示一个常规情况, 这时使用候选bean进行预测
			predictedType = predictBeanType(beanName, mbd, typesToMatch);
			if (predictedType == null) {
				// TODO 如果还没有, 返回false表示不匹配
				return false;
			}
		}

		// Attempt to get the actual ResolvableType for the bean.
		ResolvableType beanType = null;

		// If it's a FactoryBean, we want to look at what it creates, not the factory class.
		// TODO 对预测的类型进行判断
		if (FactoryBean.class.isAssignableFrom(predictedType)) {
			// TODO 如果是工厂类型
			if (beanInstance == null && !isFactoryDereference) {
				// TODO 没有对应的bean单例实例, 且要得到的bean不是工厂类(不是由'&'开头)时, 取得工厂类的类型
				beanType = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit);
				// TODO 然后设置到预测类型上, 如果还没有解析到, 返回false表示不匹配
				predictedType = beanType.resolve();
				if (predictedType == null) {
					return false;
				}
			}
		}
		else if (isFactoryDereference) {
			// TODO 要得到的bean是工厂类时
			// Special case: A SmartInstantiationAwareBeanPostProcessor returned a non-FactoryBean
			// type, but we nevertheless are being asked to dereference a FactoryBean...
			// Let's check the original bean class and proceed with it if it is a FactoryBean.
			// TODO 预测要匹配的bean是否为工厂类. 如果不是工厂类, 或无法预测类型时, 返回false表示预测失败
			predictedType = predictBeanType(beanName, mbd, FactoryBean.class);
			if (predictedType == null || !FactoryBean.class.isAssignableFrom(predictedType)) {
				return false;
			}
		}

		// We don't have an exact type but if bean definition target type or the factory
		// method return type matches the predicted type then we can use that.
		// TODO 如果到这还没有解析出type, 就看候选bean所引用的Class对象, 以及工厂方法的返回类型是否匹配了
		if (beanType == null) {
			// TODO 先看候选bean所引用的Class对象
			ResolvableType definedType = mbd.targetType;
			if (definedType == null) {
				// TODO 候选bean所引用的Class对象不存在时(有可能是还没解析), 看工厂方法返回类型type
				definedType = mbd.factoryMethodReturnType;
			}
			if (definedType != null && definedType.resolve() == predictedType) {
				// TODO 如果存在候选bean所引用的Class对象, 或工厂方法的返回类型, 并且该类型与要预测的类型一致时, 用此类型做为bean的类型
				beanType = definedType;
			}
		}

		// If we have a bean type use it so that generics are considered
		if (beanType != null) {
			// TODO 得到bean了的实际, 判断是否与指定type匹配
			return typeToMatch.isAssignableFrom(beanType);
		}

		// If we don't have a bean type, fallback to the predicted type
		// TODO 如果最后还是没有bean的实际类型, 则用前面得到的预测类型进行判断
		return typeToMatch.isAssignableFrom(predictedType);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
	}

	@Override
	// TODO 根据bean名取得bean的类型
	public @Nullable Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		// TODO 根据bean名取得对应的Class, 并且在需要时可以对工厂类进行初始化
		return getType(name, true);
	}

	@Override
	// TODO 根据bean名取得bean的类型, 支持工厂类初始化功能
	public @Nullable Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		// TODO 取得bean注册的id, 用于处理别名, '&'开头的工厂方法
		String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		// TODO 取得指定bean的单例实例, 这里传入了false表示不支持提前初始化
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			// TODO 如果bean存在单例实例, 且不是NullBean时, 检查bean实例的类型
			if (beanInstance instanceof FactoryBean<?> factoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
				// TODO bean实例是工厂类(实现了FactoryBean接口), 但要取得的bean不是由'&'开头的工厂类时, 通过bean实例的getObjectType()
				//  方法取得工厂实例的类型(工厂类的bean需要用getObject()取得bean对象, 直接取得到的会是工厂的实例. 对应的取得bean
				//  对象的类型也要用getObjectType())
				return getTypeForFactoryBean(factoryBean);
			}
			else {
				// TODO 其他情况返回bean实例的class
				return beanInstance.getClass();
			}
		}

		// No singleton instance found -> check bean definition.
		// TODO 没有单例实例时, 就要从父容器中查检了
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// TODO 有父容器, 且当前容器的没有注册过正在操作的bean时(注册中心beanDefinitionMap中没有对应的bd), 到父容器中去取bean的类型
			return parentBeanFactory.getType(originalBeanName(name));
		}
		// TODO 到这里就表示当前操作的bean没有单例实例, 并且当前容器没有父容器, 或者当前容器注册过当前操作的bean的情况.
		//  用当前操作的bean与其双亲的属性进行合并, 生成一个RootBeanDefinition
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		// TODO 走到这里时, 会有以下几种情况:
		//  1. 并没有被代理的原始bd, 即非代理类;
		//  2. 或要取得的bean是工厂类;
		//  3. 或在有被代理的原始bd, 且要取得的bean不是工厂类时, 无法预测被被包装过的源bd类型;
		//  预测一下mbd的类型(beanClass), 即bean对应的class
		Class<?> beanClass = predictBeanType(beanName, mbd);

		if (beanClass != null) {
			// Check bean class whether we're dealing with a FactoryBean.
			if (FactoryBean.class.isAssignableFrom(beanClass)) {
				if (!BeanFactoryUtils.isFactoryDereference(name)) {
					// If it's a FactoryBean, we want to look at what it creates, not at the factory class.
					beanClass = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit).resolve();
				}
			}
			else if (BeanFactoryUtils.isFactoryDereference(name)) {
				return null;
			}
		}

		if (beanClass == null) {
			// Check decorated bean definition, if any: We assume it'll be easier
			// to determine the decorated bean's type than the proxy's type.
			// TODO mbd有可能会是一个代理里, 先看一下mbd是否持有一个被包装过的bd(AOP时, 代理bd中会包含代理目标bd), 即原始的bd??
			BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
			if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
				// TODO 存在被代理的原始bd, 且要得到的bean不是工厂类的情况时('&'开头), 用原始bd与mbd(其父bd)的属性进行合并. 在这个过程中,
				//  其scope会根据mbd的scope而发生变化: mbd如果不是单例, 且被包装过的bd是单例时, 会用mbd的scope来替换被包装过的目标bean的scope
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				// TODO 预测被代理的原始bd的类型, 返回第一个匹配的类型, 如果没有, 返回的是null
				Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
				if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
					// TODO 一旦取得目标类型, 且其目标类型不是FactoryBean类型时, 直接返回代理目标类
					return targetClass;
				}
			}
		}

		// TODO 如果要取得的bean是由工厂创建的, 直接返回合并过的bd的类型
		return beanClass;
	}

	@Override
	public String[] getAliases(String name) {
		String beanName = transformedBeanName(name);
		List<String> aliases = new ArrayList<>();
		boolean hasFactoryPrefix = (!name.isEmpty() && name.charAt(0) == BeanFactory.FACTORY_BEAN_PREFIX_CHAR);
		String fullBeanName = beanName;
		if (hasFactoryPrefix) {
			fullBeanName = FACTORY_BEAN_PREFIX + beanName;
		}
		if (!fullBeanName.equals(name)) {
			aliases.add(fullBeanName);
		}
		String[] retrievedAliases = super.getAliases(beanName);
		String prefix = (hasFactoryPrefix ? FACTORY_BEAN_PREFIX : "");
		for (String retrievedAlias : retrievedAliases) {
			String alias = prefix + retrievedAlias;
			if (!alias.equals(name)) {
				aliases.add(alias);
			}
		}
		if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null) {
				aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
			}
		}
		return StringUtils.toStringArray(aliases);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public @Nullable BeanFactory getParentBeanFactory() {
		return this.parentBeanFactory;
	}

	@Override
	public boolean containsLocalBean(String name) {
		String beanName = transformedBeanName(name);
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
			throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
		}
		if (this == parentBeanFactory) {
			throw new IllegalStateException("Cannot set parent bean factory to self");
		}
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setBeanClassLoader(@Nullable ClassLoader beanClassLoader) {
		this.beanClassLoader = (beanClassLoader != null ? beanClassLoader : ClassUtils.getDefaultClassLoader());
	}

	@Override
	public @Nullable ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setTempClassLoader(@Nullable ClassLoader tempClassLoader) {
		this.tempClassLoader = tempClassLoader;
	}

	@Override
	public @Nullable ClassLoader getTempClassLoader() {
		return this.tempClassLoader;
	}

	@Override
	public void setCacheBeanMetadata(boolean cacheBeanMetadata) {
		this.cacheBeanMetadata = cacheBeanMetadata;
	}

	@Override
	public boolean isCacheBeanMetadata() {
		return this.cacheBeanMetadata;
	}

	@Override
	public void setBeanExpressionResolver(@Nullable BeanExpressionResolver resolver) {
		this.beanExpressionResolver = resolver;
	}

	@Override
	public @Nullable BeanExpressionResolver getBeanExpressionResolver() {
		return this.beanExpressionResolver;
	}

	@Override
	public void setConversionService(@Nullable ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@Override
	public @Nullable ConversionService getConversionService() {
		return this.conversionService;
	}

	@Override
	public void addPropertyEditorRegistrar(PropertyEditorRegistrar registrar) {
		Assert.notNull(registrar, "PropertyEditorRegistrar must not be null");
		if (registrar.overridesDefaultEditors()) {
			this.defaultEditorRegistrars.add(registrar);
		}
		else {
			this.propertyEditorRegistrars.add(registrar);
		}
	}

	/**
	 * Return the set of PropertyEditorRegistrars.
	 */
	public Set<PropertyEditorRegistrar> getPropertyEditorRegistrars() {
		return this.propertyEditorRegistrars;
	}

	@Override
	public void registerCustomEditor(Class<?> requiredType, Class<? extends PropertyEditor> propertyEditorClass) {
		Assert.notNull(requiredType, "Required type must not be null");
		Assert.notNull(propertyEditorClass, "PropertyEditor class must not be null");
		this.customEditors.put(requiredType, propertyEditorClass);
	}

	@Override
	public void copyRegisteredEditorsTo(PropertyEditorRegistry registry) {
		registerCustomEditors(registry);
	}

	/**
	 * Return the map of custom editors, with Classes as keys and PropertyEditor classes as values.
	 */
	public Map<Class<?>, Class<? extends PropertyEditor>> getCustomEditors() {
		return this.customEditors;
	}

	@Override
	public void setTypeConverter(TypeConverter typeConverter) {
		this.typeConverter = typeConverter;
	}

	/**
	 * Return the custom TypeConverter to use, if any.
	 * @return the custom TypeConverter, or {@code null} if none specified
	 */
	protected @Nullable TypeConverter getCustomTypeConverter() {
		return this.typeConverter;
	}

	@Override
	public TypeConverter getTypeConverter() {
		// TODO 取得自定义的类型转换器, 如果有直接返回
		TypeConverter customConverter = getCustomTypeConverter();
		if (customConverter != null) {
			return customConverter;
		}
		else {
			// Build default TypeConverter, registering custom editors.
			// TODO 如果没有, 就返回一个默认的SimpleTypeConverter解析器
			SimpleTypeConverter typeConverter = new SimpleTypeConverter();
			typeConverter.setConversionService(getConversionService());
			registerCustomEditors(typeConverter);
			return typeConverter;
		}
	}

	@Override
	// TODO 增加Value解析器, 有两个地方会调用这个方法添加解析器:
	//  1. AbstractApplicationContext#finishBeanFactoryInitialization(): 容器初始化时
	//  2. PropertyPlaceholderConfigurer#processProperties():
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public boolean hasEmbeddedValueResolver() {
		return !this.embeddedValueResolvers.isEmpty();
	}

	@Override
	public @Nullable String resolveEmbeddedValue(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String result = value;
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			// TODO 用注册的实现了 StringValueResolver 接口的解析器挨个儿解析传入的值, 以下实现类实现了解析方法:
			//  1. EmbeddedValueResolver: 用于解析 @Value 注解的值, 当配置了表达式解析器时, 还可以解析 SpEL 表达式
			//  2. PropertyPlaceholderConfigurer$PlaceholderResolvingStringValueResolver: 用 PropertyPlaceholderHelper 提供的
			//     实现处理值, 这里会替换掉${}
			//  3. PropertyPlaceholderConfigurer#processProperties(): 传入的是一个 Lambda 表达式, 如果手动配置了 PropertyPlaceholderConfigurer
			//     或 PropertySourcesPlaceholderConfigurer, 并且 ignoreUnresolvablePlaceholders 没有设置为 true 时, 当遇到非法占位符时, 会抛出异常
			//  4. AbstractApplicationContext#finishBeanFactoryInitialization(): 在容器初始化时, 如果容器发现没设置解析器,
			//     则会提供一个默认的, 可以解析非法占位符的解析器(无法解析占位符时, 会原样输出)
			result = resolver.resolveStringValue(result);
			if (result == null) {
				return null;
			}
		}
		return result;
	}

	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
		synchronized (this.beanPostProcessors) {
			// Remove from old position, if any
			this.beanPostProcessors.remove(beanPostProcessor);
			// Add to end of list
			this.beanPostProcessors.add(beanPostProcessor);
		}
	}

	/**
	 * Add new BeanPostProcessors that will get applied to beans created
	 * by this factory. To be invoked during factory configuration.
	 * @since 5.3
	 * @see #addBeanPostProcessor
	 */
	public void addBeanPostProcessors(Collection<? extends BeanPostProcessor> beanPostProcessors) {
		synchronized (this.beanPostProcessors) {
			// Remove from old position, if any
			this.beanPostProcessors.removeAll(beanPostProcessors);
			// Add to end of list
			this.beanPostProcessors.addAll(beanPostProcessors);
		}
	}

	@Override
	public int getBeanPostProcessorCount() {
		return this.beanPostProcessors.size();
	}

	/**
	 * Return the list of BeanPostProcessors that will get applied
	 * to beans created with this factory.
	 */
	public List<BeanPostProcessor> getBeanPostProcessors() {
		return this.beanPostProcessors;
	}

	/**
	 * Return the internal cache of pre-filtered post-processors,
	 * freshly (re-)building it if necessary.
	 * @since 5.3
	 */
	BeanPostProcessorCache getBeanPostProcessorCache() {
		synchronized (this.beanPostProcessors) {
			BeanPostProcessorCache bppCache = this.beanPostProcessorCache;
			if (bppCache == null) {
				bppCache = new BeanPostProcessorCache();
				for (BeanPostProcessor bpp : this.beanPostProcessors) {
					if (bpp instanceof InstantiationAwareBeanPostProcessor instantiationAwareBpp) {
						bppCache.instantiationAware.add(instantiationAwareBpp);
						if (bpp instanceof SmartInstantiationAwareBeanPostProcessor smartInstantiationAwareBpp) {
							bppCache.smartInstantiationAware.add(smartInstantiationAwareBpp);
						}
					}
					if (bpp instanceof DestructionAwareBeanPostProcessor destructionAwareBpp) {
						bppCache.destructionAware.add(destructionAwareBpp);
					}
					if (bpp instanceof MergedBeanDefinitionPostProcessor mergedBeanDefBpp) {
						bppCache.mergedDefinition.add(mergedBeanDefBpp);
					}
				}
				this.beanPostProcessorCache = bppCache;
			}
			return bppCache;
		}
	}

	private void resetBeanPostProcessorCache() {
		synchronized (this.beanPostProcessors) {
			this.beanPostProcessorCache = null;
		}
	}

	/**
	 * Return whether this factory holds a InstantiationAwareBeanPostProcessor
	 * that will get applied to singleton beans on creation.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
	 */
	protected boolean hasInstantiationAwareBeanPostProcessors() {
		return !getBeanPostProcessorCache().instantiationAware.isEmpty();
	}

	/**
	 * Return whether this factory holds a DestructionAwareBeanPostProcessor
	 * that will get applied to singleton beans on shutdown.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean hasDestructionAwareBeanPostProcessors() {
		return !getBeanPostProcessorCache().destructionAware.isEmpty();
	}

	@Override
	public void registerScope(String scopeName, Scope scope) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		Assert.notNull(scope, "Scope must not be null");
		if (SCOPE_SINGLETON.equals(scopeName) || SCOPE_PROTOTYPE.equals(scopeName)) {
			throw new IllegalArgumentException("Cannot replace existing scopes 'singleton' and 'prototype'");
		}
		Scope previous = this.scopes.put(scopeName, scope);
		if (previous != null && previous != scope) {
			if (logger.isDebugEnabled()) {
				logger.debug("Replacing scope '" + scopeName + "' from [" + previous + "] to [" + scope + "]");
			}
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Registering scope '" + scopeName + "' with implementation [" + scope + "]");
			}
		}
	}

	@Override
	public String[] getRegisteredScopeNames() {
		return StringUtils.toStringArray(this.scopes.keySet());
	}

	@Override
	public @Nullable Scope getRegisteredScope(String scopeName) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		return this.scopes.get(scopeName);
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		Assert.notNull(applicationStartup, "ApplicationStartup must not be null");
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		Assert.notNull(otherFactory, "BeanFactory must not be null");
		setBeanClassLoader(otherFactory.getBeanClassLoader());
		setCacheBeanMetadata(otherFactory.isCacheBeanMetadata());
		setBeanExpressionResolver(otherFactory.getBeanExpressionResolver());
		setConversionService(otherFactory.getConversionService());
		if (otherFactory instanceof AbstractBeanFactory otherAbstractFactory) {
			this.defaultEditorRegistrars.addAll(otherAbstractFactory.defaultEditorRegistrars);
			this.propertyEditorRegistrars.addAll(otherAbstractFactory.propertyEditorRegistrars);
			this.customEditors.putAll(otherAbstractFactory.customEditors);
			this.typeConverter = otherAbstractFactory.typeConverter;
			this.beanPostProcessors.addAll(otherAbstractFactory.beanPostProcessors);
			this.scopes.putAll(otherAbstractFactory.scopes);
		}
		else {
			setTypeConverter(otherFactory.getTypeConverter());
			String[] otherScopeNames = otherFactory.getRegisteredScopeNames();
			for (String scopeName : otherScopeNames) {
				this.scopes.put(scopeName, otherFactory.getRegisteredScope(scopeName));
			}
		}
	}

	/**
	 * Return a 'merged' BeanDefinition for the given bean name,
	 * merging a child bean definition with its parent if necessary.
	 * <p>This {@code getMergedBeanDefinition} considers bean definition
	 * in ancestors as well.
	 * @param name the name of the bean to retrieve the merged definition for
	 * (may be an alias)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	@Override
	public BeanDefinition getMergedBeanDefinition(String name) throws BeansException {
		// TODO 取得要得到的bean的最终名字
		String beanName = transformedBeanName(name);
		// Efficiently check whether bean definition exists in this factory.
		if (getParentBeanFactory() instanceof ConfigurableBeanFactory parent && !containsBeanDefinition(beanName)) {
			// TODO 注册中心beanDefinitionMap没有对应的bean, 并且其父容器是ConfigurableBeanFactory类型时,
			//  从父容器中取得指定的bean(如果有双亲, 则会返回合并了双亲属性的RootBeanDefinition)
			//  这里表示当前容器没有要取得的bean时, 会一步步递归进入父容器, 直到最深层
			return parent.getMergedBeanDefinition(beanName);
		}
		// Resolve merged bean definition locally.
		// TODO 从当前工厂中, 根据bean来返回一个合并了双亲属性的RootBeanDefinition
		return getMergedLocalBeanDefinition(beanName);
	}

	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		// TODO 取得 bean 的真实名字
		String beanName = transformedBeanName(name);
		// TODO 取得 bean 的单例实例
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			// TODO 缓存中存在时, 判断实例是否为 FactoryBean 类型
			return (beanInstance instanceof FactoryBean);
		}
		// No singleton instance found -> check bean definition.
		// TODO bean 实例不存在时, 就需要判断当前 bean 是否在注册中心中, 以及父容器是否是 ConfigurableBeanFactory 类型
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory cbf) {
			// No bean definition found in this factory -> delegate to parent.
			// TODO 委托给父容器进行判断
			return cbf.isFactoryBean(name);
		}
		// TODO 取得一个合并了双亲属性的 bd, 然后再判断是否为 FactoryBean
		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

	@Override
	public boolean isActuallyInCreation(String beanName) {
		return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
	}

	/**
	 * Return whether the specified prototype bean is currently in creation
	 * (within the current thread).
	 * @param beanName the name of the bean
	 */
	protected boolean isPrototypeCurrentlyInCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		return (curVal != null &&
				(curVal.equals(beanName) || (curVal instanceof Set<?> set && set.contains(beanName))));
	}

	/**
	 * Callback before prototype creation.
	 * <p>The default implementation registers the prototype as currently in creation.
	 * @param beanName the name of the prototype about to be created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void beforePrototypeCreation(String beanName) {
		// TODO 取得当前线程中正在创建的 prototype 类型的bean对象
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal == null) {
			// TODO 没有, 则把要创建的对象放到当前线程中
			this.prototypesCurrentlyInCreation.set(beanName);
		}
		else if (curVal instanceof String strValue) {
			// TODO 如果是 String 类型, 构造一个 set, 把当前线程中正在创建的对象和要创建的 bean 都放进去
			Set<String> beanNameSet = CollectionUtils.newHashSet(2);
			beanNameSet.add(strValue);
			beanNameSet.add(beanName);
			this.prototypesCurrentlyInCreation.set(beanNameSet);
		}
		else {
			// TODO 其他类型时, 把当前创建的 bean 也放进去
			Set<String> beanNameSet = (Set<String>) curVal;
			beanNameSet.add(beanName);
		}
	}

	/**
	 * Callback after prototype creation.
	 * <p>The default implementation marks the prototype as not in creation anymore.
	 * @param beanName the name of the prototype that has been created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void afterPrototypeCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal instanceof String) {
			this.prototypesCurrentlyInCreation.remove();
		}
		else if (curVal instanceof Set<?> beanNameSet) {
			beanNameSet.remove(beanName);
			if (beanNameSet.isEmpty()) {
				this.prototypesCurrentlyInCreation.remove();
			}
		}
	}

	@Override
	public void destroyBean(String beanName, Object beanInstance) {
		destroyBean(beanName, beanInstance, getMergedLocalBeanDefinition(beanName));
	}

	/**
	 * Destroy the given bean instance (usually a prototype instance
	 * obtained from this factory) according to the given bean definition.
	 * @param beanName the name of the bean definition
	 * @param bean the bean instance to destroy
	 * @param mbd the merged bean definition
	 */
	protected void destroyBean(String beanName, Object bean, RootBeanDefinition mbd) {
		new DisposableBeanAdapter(
				bean, beanName, mbd, getBeanPostProcessorCache().destructionAware).destroy();
	}

	@Override
	public void destroyScopedBean(String beanName) {
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isSingleton() || mbd.isPrototype()) {
			throw new IllegalArgumentException(
					"Bean name '" + beanName + "' does not correspond to an object in a mutable scope");
		}
		String scopeName = mbd.getScope();
		Scope scope = this.scopes.get(scopeName);
		if (scope == null) {
			throw new IllegalStateException("No Scope SPI registered for scope name '" + scopeName + "'");
		}
		Object bean = scope.remove(beanName);
		if (bean != null) {
			destroyBean(beanName, bean, mbd);
		}
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Return the bean name, stripping out the factory dereference prefix if necessary,
	 * and resolving aliases to canonical names.
	 * @param name the user-specified name
	 * @return the transformed bean name
	 */
	protected String transformedBeanName(String name) {
		// TODO 工厂类是以 '&' 开头的, BeanFactoryUtils.transformedBeanName() 方法用于去掉 '&'
		//  然后再通过别名得到最终 bean 的名字
		return canonicalName(BeanFactoryUtils.transformedBeanName(name));
	}

	/**
	 * Determine the original bean name, resolving locally defined aliases to canonical names.
	 * @param name the user-specified name
	 * @return the original bean name
	 */
	protected String originalBeanName(String name) {
		String beanName = transformedBeanName(name);
		if (!name.isEmpty() && name.charAt(0) == BeanFactory.FACTORY_BEAN_PREFIX_CHAR) {
			beanName = FACTORY_BEAN_PREFIX + beanName;
		}
		// TODO 这里得到的是bean的真实名字, 如果其为一个工厂类, 则会再真实名字前再加上'&'
		return beanName;
	}

	/**
	 * Initialize the given BeanWrapper with the custom editors registered
	 * with this factory. To be called for BeanWrappers that will create
	 * and populate bean instances.
	 * <p>The default implementation delegates to {@link #registerCustomEditors}.
	 * Can be overridden in subclasses.
	 * @param bw the BeanWrapper to initialize
	 */
	protected void initBeanWrapper(BeanWrapper bw) {
		bw.setConversionService(getConversionService());
		// TODO 注册属性编辑器, BeanWrapper 本身实现了 PropertyEditorRegistry 接口
		registerCustomEditors(bw);
	}

	/**
	 * Initialize the given PropertyEditorRegistry with the custom editors
	 * that have been registered with this BeanFactory.
	 * <p>To be called for BeanWrappers that will create and populate bean
	 * instances, and for SimpleTypeConverter used for constructor argument
	 * and factory method type conversion.
	 * @param registry the PropertyEditorRegistry to initialize
	 */
	protected void registerCustomEditors(PropertyEditorRegistry registry) {
		if (registry instanceof PropertyEditorRegistrySupport registrySupport) {
			registrySupport.useConfigValueEditors();
			if (!this.defaultEditorRegistrars.isEmpty()) {
				// Optimization: lazy overriding of default editors only when needed
				registrySupport.setDefaultEditorRegistrar(new BeanFactoryDefaultEditorRegistrar());
			}
		}
		else if (!this.defaultEditorRegistrars.isEmpty()) {
			// Fallback: proactive overriding of default editors
			applyEditorRegistrars(registry, this.defaultEditorRegistrars);
		}

		if (!this.propertyEditorRegistrars.isEmpty()) {
			applyEditorRegistrars(registry, this.propertyEditorRegistrars);
		}
		if (!this.customEditors.isEmpty()) {
			// TODO 注册一遍 PropertyEditors 类型的自定义属性编辑器
			this.customEditors.forEach((requiredType, editorClass) ->
					registry.registerCustomEditor(requiredType, BeanUtils.instantiateClass(editorClass)));
		}
	}

	private void applyEditorRegistrars(PropertyEditorRegistry registry, Set<PropertyEditorRegistrar> registrars) {
		for (PropertyEditorRegistrar registrar : registrars) {
			try {
				// TODO 注册一遍PropertyEditorRegistrars类型的自定义的属性编辑器
				registrar.registerCustomEditors(registry);
			}
			catch (BeanCreationException ex) {
				Throwable rootCause = ex.getMostSpecificCause();
				if (rootCause instanceof BeanCurrentlyInCreationException bce) {
					String bceBeanName = bce.getBeanName();
					if (bceBeanName != null && isCurrentlyInCreation(bceBeanName)) {
						if (logger.isDebugEnabled()) {
							logger.debug("PropertyEditorRegistrar [" + registrar.getClass().getName() +
									"] failed because it tried to obtain currently created bean '" +
									ex.getBeanName() + "': " + ex.getMessage());
						}
						onSuppressedException(ex);
						return;
					}
				}
				throw ex;
			}
		}
	}

	/**
	 * Return a merged RootBeanDefinition, traversing the parent bean definition
	 * if the specified bean corresponds to a child bean definition.
	 * @param beanName the name of the bean to retrieve the merged definition for 要取得的bean的名字
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	// TODO 用给定的 bean 与其可能存在的双亲进行合并, 生成并返回一个包含了双亲属性的 bean 的 RootBeanDefinition. 支持本地缓存
	protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
		// Quick check on the concurrent map first, with minimal locking.
		// TODO 先尝试从缓存中取(里面放的都是 RootBeanDefinition), 取到后如果没有过期则直接返回
		RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
		if (mbd != null && !mbd.stale) {
			return mbd;
		}
		// TODO 如果缓存中没有, 或者 RootBeanDefinition 已经失效时, 重新获取 BeanDefinition, 返回一个合并了双亲属性的 RootBeanDefinition, 流程是:
		//  1. 从注册中心 beanDefinitionMap 中取得对应的 BeanDefinition
		//  2. 在当前容器中取得取得对应的 BeanDefinition (可能是合并了双亲 BeanDefinition 属性的 RootBeanDefinition)
		return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
	}

	/**
	 * Return a RootBeanDefinition for the given top-level bean, by merging with
	 * the parent if the given bean's definition is a child bean definition.
	 * @param beanName the name of the bean definition 要取得的bean的名字
	 * @param bd the original bean definition (Root/ChildBeanDefinition) 要取得的bean所对应的原始的bean definition,
	 *              有可能是一个RootBeanDefinition, 也可能是一个ChildBeanDefinition, 是bean对应的bean definition
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	// TODO 用给定的 bean 与其可能存在的双亲进行合并, 生成并返回合并双亲属性的顶层 RootBeanDefinition
	protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
			throws BeanDefinitionStoreException {
		// TODO 根据指定的 bean 取得一个合并双亲属性的 RootBeanDefinition, 此 bean 为不被其他 BeanDefinition 所包含的顶层 BeanDefinition
		return getMergedBeanDefinition(beanName, bd, null);
	}

	/**
	 * Return a RootBeanDefinition for the given bean, by merging with the
	 * parent if the given bean's definition is a child bean definition.
	 * @param beanName the name of the bean definition 容器内bean的映射名
	 * @param bd the original bean definition (Root/ChildBeanDefinition) 要取得的bean所对应的原始的bean definition,
	 *              有可能是一个RootBeanDefinition, 也可能是一个ChildBeanDefinition, 是bean对应的bean definition
	 * @param containingBd the containing bean definition in case of inner bean,
	 * or {@code null} in case of a top-level 包含着要取得的bean definition的父bean definition. 顶层bd时, 此参数为null
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	// TODO 用给定的 bean 与其可能存在的双亲进行合并, 生成并返回合并双亲属性的 RootBeanDefinition, 支持 BeanDefinition的嵌套
	protected RootBeanDefinition getMergedBeanDefinition(
			String beanName, BeanDefinition bd, @Nullable BeanDefinition containingBd)
			throws BeanDefinitionStoreException {
		// TODO 从缓存中取得合并过的 RootBeanDefinition 时需要进行同步
		synchronized (this.mergedBeanDefinitions) {
			// TODO 用于返回的合并了双亲属性的 RootBeanDefinition
			RootBeanDefinition mbd = null;
			// TODO 表示正在处理的 RootBeanDefinition 的前一个状态(可能是null, 或是保存一个失效的 RootBeanDefinition)
			RootBeanDefinition previous = null;

			// Check with full lock now in order to enforce the same merged instance.
			if (containingBd == null) {
				// TODO 当前要取得的 bean 没有包含在其他 bean 中时, 表示其为一个顶层 bean (RootBeanDefinition), 先尝试从缓存取得 RootBeanDefinition
				mbd = this.mergedBeanDefinitions.get(beanName);
			}

			if (mbd == null || mbd.stale) {
				// TODO 缓存中没有, 或其已失效时, 先将当前 RootBeanDefinition 保存到前一个状态中, 然后再生成新的 RootBeanDefinition
				//  生成 RootBeanDefinition 时有两种情况:
				//  1. RootBeanDefinition: 当前的 BeanDefinition 为没有双亲的顶层 BeanDefinition
				//  2. ChildBeanDefinition: 当前的 BeanDefinition 有双亲的子 BeanDefinition
				previous = mbd;
				if (bd.getParentName() == null) {
					// Use copy of given root bean definition.
					// TODO 顶层 BeanDefinition 是没有双亲的, 因为合并动作会直接递归到最顶层的bd, 所以这里也会做为合并退出的地方.
					if (bd instanceof RootBeanDefinition rootBeanDef) {
						// TODO 当前的 BeanDefinition 是 RootBeanDefinition 时, 克隆一个副本
						mbd = rootBeanDef.cloneBeanDefinition();
					}
					else {
						// TODO 其他情况下, BeanDefinition 会是 GenericBeanDefinition 类型. 用其创建一个 RootBeanDefinition
						mbd = new RootBeanDefinition(bd);
					}
				}
				else {
					// Child bean definition: needs to be merged with parent.
					// TODO 到这里表示 BeanDefinition 为 ChildBeanDefinition 类型, 需要与双亲进行合并
					BeanDefinition pbd;
					try {
						// TODO 取得双亲的名字
						String parentBeanName = transformedBeanName(bd.getParentName());
						if (!beanName.equals(parentBeanName)) {
							// TODO 双亲不是要取得的 bean 时, 在当前容器中取得双亲的 BeanDefinition. 因为双亲也可能是 ChildBeanDefinition 类型,
							//  所以这边会进行递归, 直到最内层的顶层 RootBeanDefinition (上面的分支)
							pbd = getMergedBeanDefinition(parentBeanName);
						}
						else {
							// TODO 双亲就是要找 bean 时, 尝试在父容器中取得双亲的 BeanDefinition, 即 pdb
							if (getParentBeanFactory() instanceof ConfigurableBeanFactory parent) {
								// TODO 如果父容器是 ConfigurableBeanFactory 时(可配置的容器), 则到父容器中取得其双亲.
								//  这里会一直递归进入最深层, 直到得到 pbd, 最终还是进行上面的分支
								pbd = parent.getMergedBeanDefinition(parentBeanName);
							}
							else {
								// TODO 其他情况会抛 NoSuchBeanDefinitionException 异常, 无法在没有父抽象容器时解析 bean
								throw new NoSuchBeanDefinitionException(parentBeanName,
										"Parent name '" + parentBeanName + "' is equal to bean name '" + beanName +
												"': cannot be resolved without a ConfigurableBeanFactory parent");
							}
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						throw new BeanDefinitionStoreException(bd.getResourceDescription(), beanName,
								"Could not resolve parent bean definition '" + bd.getParentName() + "'", ex);
					}
					// Deep copy with overridden values.
					// TODO 用双亲 BeanDefinition 生成一个做为返回的 RootBeanDefinition
					mbd = new RootBeanDefinition(pbd);
					// TODO 然后开始合并 BeanDefinition 属性. 合并的原理就是不停的用当前操作的 BeanDefinition 去覆盖双亲 BeanDefinition 的属性.
					//  得到的结果就是包含了两个 BeanDefinition 属性的 RootBeanDefinition
					mbd.overrideFrom(bd);
				}

				// Set default singleton scope, if not configured before.
				if (!StringUtils.hasLength(mbd.getScope())) {
					// TODO scope 没设置时, 自动设置默认值为单例
					mbd.setScope(SCOPE_SINGLETON);
				}

				// A bean contained in a non-singleton bean cannot be a singleton itself.
				// Let's correct this on the fly here, since this might be the result of
				// parent-child merging for the outer bean, in which case the original inner bean
				// definition will not have inherited the merged outer bean's singleton status.
				if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
					// TODO 对于被包含的单例的 bean 来说, 如果包含他的 bean 不是单例的, 需要将他的作用域改为外层bean的作用域
					mbd.setScope(containingBd.getScope());
				}

				// Cache the merged bean definition for the time being
				// (it might still get re-merged later on in order to pick up metadata changes)
				if (containingBd == null && (isCacheBeanMetadata() || isBeanEligibleForMetadataCaching(beanName))) {
					// TODO 没有被包含的 bean 如果允许进行缓存, 直接放入 mergedBeanDefinitions 缓存
					cacheMergedBeanDefinition(mbd, beanName);
				}
			}
			if (previous != null) {
				// TODO 继承上一个状态的部分属性
				copyRelevantMergedBeanDefinitionCaches(previous, mbd);
			}
			return mbd;
		}
	}

	private void copyRelevantMergedBeanDefinitionCaches(RootBeanDefinition previous, RootBeanDefinition mbd) {
		if (ObjectUtils.nullSafeEquals(mbd.getBeanClassName(), previous.getBeanClassName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryBeanName(), previous.getFactoryBeanName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryMethodName(), previous.getFactoryMethodName())) {
			ResolvableType targetType = mbd.targetType;
			ResolvableType previousTargetType = previous.targetType;
			if (targetType == null || targetType.equals(previousTargetType)) {
				mbd.targetType = previousTargetType;
				mbd.isFactoryBean = previous.isFactoryBean;
				mbd.resolvedTargetType = previous.resolvedTargetType;
				mbd.factoryMethodReturnType = previous.factoryMethodReturnType;
				mbd.factoryMethodToIntrospect = previous.factoryMethodToIntrospect;
			}
			if (previous.hasMethodOverrides()) {
				mbd.setMethodOverrides(new MethodOverrides(previous.getMethodOverrides()));
			}
		}
	}

	/**
	 * Cache the given merged bean definition.
	 * <p>Subclasses can override this to derive additional cached state
	 * from the final post-processed bean definition.
	 * @param mbd the merged bean definition to cache
	 * @param beanName the name of the bean
	 * @since 6.2.6
	 */
	protected void cacheMergedBeanDefinition(RootBeanDefinition mbd, String beanName) {
		this.mergedBeanDefinitions.put(beanName, mbd);
	}

	/**
	 * Check the given merged bean definition,
	 * potentially throwing validation exceptions.
	 * @param mbd the merged bean definition to check
	 * @param beanName the name of the bean
	 * @param args the arguments for bean creation, if any
	 */
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, @Nullable Object @Nullable [] args) {
		if (mbd.isAbstract()) {
			throw new BeanIsAbstractException(beanName);
		}
	}

	/**
	 * Remove the merged bean definition for the specified bean,
	 * recreating it on next access.
	 * @param beanName the bean name to clear the merged definition for
	 */
	protected void clearMergedBeanDefinition(String beanName) {
		RootBeanDefinition bd = this.mergedBeanDefinitions.get(beanName);
		if (bd != null) {
			bd.stale = true;
		}
	}

	/**
	 * Clear the merged bean definition cache, removing entries for beans
	 * which are not considered eligible for full metadata caching yet.
	 * <p>Typically triggered after changes to the original bean definitions,
	 * for example, after applying a {@code BeanFactoryPostProcessor}. Note that metadata
	 * for beans which have already been created at this point will be kept around.
	 * @since 4.2
	 */
	public void clearMetadataCache() {
		this.mergedBeanDefinitions.forEach((beanName, bd) -> {
			if (!isBeanEligibleForMetadataCaching(beanName)) {
				bd.stale = true;
			}
		});
	}

	/**
	 * Resolve the bean class for the specified bean definition,
	 * resolving a bean class name into a Class reference (if necessary)
	 * and storing the resolved Class in the bean definition for further use.
	 * @param mbd the merged bean definition to determine the class for
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the resolved bean class (or {@code null} if none)
	 * @throws CannotLoadBeanClassException if we failed to load the class
	 */
	// TODO 对 bean 进行解析加载
	protected @Nullable Class<?> resolveBeanClass(RootBeanDefinition mbd, String beanName, Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {

		try {
			if (mbd.hasBeanClass()) {
				// TODO mbd 是 class 引用, 而不是全限定名时, 直接返回其对应的 bean 引用
				return mbd.getBeanClass();
			}
			// TODO 根据类型从 mbd 中的全限定名取得对应的 class
			Class<?> beanClass = doResolveBeanClass(mbd, typesToMatch);
			if (mbd.hasBeanClass()) {
				mbd.prepareMethodOverrides();
			}
			return beanClass;
		}
		catch (ClassNotFoundException ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (LinkageError err) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), err);
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbd.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}
	}

	// TODO 验证类全称类名, 并利用类加载器解析获取 class
	private @Nullable Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
			throws ClassNotFoundException {
		// TODO 取得当前的类加载器, 同时设置为动态加载器
		ClassLoader beanClassLoader = getBeanClassLoader();
		ClassLoader dynamicLoader = beanClassLoader;
		boolean freshResolve = false;

		if (!ObjectUtils.isEmpty(typesToMatch)) {
			// TODO typesToMatch 是个可变长参数:
			//  1. AbstractAutowireCapableBeanFactory#createBean() 方法调用了 resolveBeanClass(), 其中在调用 doResolveBeanClass()
			//     时并没有指定这个参数, 所以对于后处理器初始化时调用的 getBean() 所创建的 bean 实例不会走到这里
			//  2. 在查找注入项的匹配 bean 时, 容器使用 isTypeMatch(String, ResolvableType, boolean) 方法来查找匹配 ResolvableType 的 bean
			//     这里的 ResolvableType 就会转化成 Class<>[] 数组, 然后用于 predictBeanType(String, RootBeanDefinition, Class<?>)
			//     方法来做类型匹配.
			// When just doing type checks (i.e. not creating an actual instance yet),
			// use the specified temporary class loader (for example, in a weaving scenario).
			// TODO tempClassLoader 是在容器初始化时, 发现支持 Load Time Weaving(LTW, AspectJ的类加载期织入)时添加到 beanFactory 容器的,
			//  会添加一个 DecoratingClassLoader 的子类 ContextTypeMatchClassLoader(其父类加载器依然是容器 bean 类加载器), 来代理 JVM 默认的类加载器:
			//      beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
			ClassLoader tempClassLoader = getTempClassLoader();
			if (tempClassLoader != null) {
				// TODO 这里就是替换了 JVM 默认的类加载器, 如果容器支持 LWT, 后面的所有操作都会是基于 AspectJ 的 classLoader了
				dynamicLoader = tempClassLoader;
				// TODO 因为替换了 JVM 默认的类加载器, 所以后面需要重新用新类加载器加载一下
				freshResolve = true;
				if (tempClassLoader instanceof DecoratingClassLoader dcl) {
					for (Class<?> typeToMatch : typesToMatch) {
						// TODO 如果是 DecoratingClassLoader 类型的自定义 ClassLoader, 这时会将指定的类型加入到排除列表,
						//  即, 指定的类型不使用 Spring 自定义 ClassLoader 进行加载, 交由 JDK 默认的 ClassLoader 进行加载
						dcl.excludeClass(typeToMatch.getName());
					}
				}
			}
		}
		// TODO 取得当前 mbd 的 beanClass 属性所对应的名字('class'属性设置的. 如果是引用, 取得 class 的 name, 如果是全限定名, 直接返回)
		String className = mbd.getBeanClassName();
		if (className != null) {
			// TODO Spring 支持以表达式的方式定义 bean, 所以得到的 className 可能是个 SpEL 表达式, 也有可能是真实的 bean 名, 需要解析一下
			Object evaluated = evaluateBeanDefinitionString(className, mbd);
			if (!className.equals(evaluated)) {
				// A dynamically resolved expression, supported as of 4.2...
				// TODO 如果解析出来的对象和传入的类名不同时, 表示其已经被 AOP 代理增强过, 下面会根据解析出的类开进行不同的操作:
				if (evaluated instanceof Class<?> clazz) {
					// TODO 如果返回的是一个Class, 直接返回
					return clazz;
				}
				else if (evaluated instanceof String name) {
					// TODO 如果返回的是一个字面量, 这时就需要用解析后的字面量做为类名进行加载
					className = name;
					freshResolve = true;
				}
				else {
					// TODO 其他情况都是解析出问题了
					throw new IllegalStateException("Invalid class name expression result: " + evaluated);
				}
			}
			if (freshResolve) {
				// TODO 需要重新对类进行加载的情况, 替换过 ClassLoader 或解析后 mbd 的 beanClass 属性设置的是字面量，才会进到这里
				// When resolving against a temporary class loader, exit early in order
				// to avoid storing the resolved Class in the bean definition.
				if (dynamicLoader != null) {
					try {
						// TODO 尝试用动态类加载器重新加载类, 用于支持 AspectJ
						return dynamicLoader.loadClass(className);
					}
					catch (ClassNotFoundException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Could not load class [" + className + "] from " + dynamicLoader + ": " + ex);
						}
					}
				}
				// TODO 加载失败, 或动态类加载器是 null, 则委托给 ClassUtils 通过反射去加载类
				return ClassUtils.forName(className, dynamicLoader);
			}
		}

		// Resolve regularly, caching the result in the BeanDefinition...
		// TODO 下面就交给 AbstractBeanDefinition 根据 mbd 进行加载了
		return mbd.resolveBeanClass(beanClassLoader);
	}

	/**
	 * Evaluate the given String as contained in a bean definition,
	 * potentially resolving it as an expression.
	 * @param value the value to check 待验证的值
	 * @param beanDefinition the bean definition that the value comes from 待验证值所在的bd
	 * @return the resolved value
	 * @see #setBeanExpressionResolver
	 */
	// TODO 评估解析 bd 中所给定的值
	protected @Nullable Object evaluateBeanDefinitionString(@Nullable String value, @Nullable BeanDefinition beanDefinition) {
		if (this.beanExpressionResolver == null) {
			return value;
		}

		Scope scope = null;
		if (beanDefinition != null) {
			String scopeName = beanDefinition.getScope();
			if (scopeName != null) {
				// TODO 取得 BeanDefinition 的作用域
				scope = getRegisteredScope(scopeName);
			}
		}
		// TODO 用 BeanExpressionResolver.evaluate(String, BeanExpressionContext) 来将 String 值做为表达式进行评估解析(value有可能是SpEL表达式).
		//  解析的过程是用 BeanExpressionContext 表达式上下文对应的 StandardEvaluationContext 取值上下文中取出 value 表达式对应的对象.
		//  Spring 在 ApplicationContext 初始化时, 通过 prepareBeanFactory() 设置了 StandardBeanExpressionResolver 做为默认解析器,
		//  所以这里调用的是 StandardBeanExpressionResolver#evaluate() 对 value 进行评估解析, 返回的结果有以下可能:
		//  1. 传入的值是个 SpEL 表达式, 返回的是解析后的值
		//  2. 字面量
		return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
	}


	/**
	 * Predict the eventual bean type (of the processed bean instance) for the
	 * specified bean. Called by {@link #getType} and {@link #isTypeMatch}.
	 * Does not need to handle FactoryBeans specifically, since it is only
	 * supposed to operate on the raw bean type.
	 * <p>This implementation is simplistic in that it is not able to
	 * handle factory methods and InstantiationAwareBeanPostProcessors.
	 * It only predicts the bean type correctly for a standard bean.
	 * To be overridden in subclasses, applying more sophisticated type detection.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition to determine the type for
	 * @param typesToMatch the types to match in case of internal type matching purposes 要匹配的类型
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type of the bean, or {@code null} if not predictable
	 */
	// TODO 预测 bean 的 type 类型
	protected @Nullable Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// TODO 首先看看这个 bean 所表示的 Class 对象. 如果有就直接返回
		Class<?> targetType = mbd.getTargetType();
		if (targetType != null) {
			return targetType;
		}
		if (mbd.getFactoryMethodName() != null) {
			// TODO bean 没有所表示的 Class 对象时, 如果其为一个工厂方法, 则类型会由工厂方法提供, 这里就不再进行预测了, 直接返回 null
			return null;
		}
		// TODO 开始解析加载 bean
		return resolveBeanClass(mbd, beanName, typesToMatch);
	}

	/**
	 * Check whether the given bean is defined as a {@link FactoryBean}.
	 * @param beanName the name of the bean
	 * @param mbd the corresponding bean definition
	 */
	protected boolean isFactoryBean(String beanName, RootBeanDefinition mbd) {
		// TODO 判断 mbd 是否是一个工厂类
		Boolean result = mbd.isFactoryBean;
		if (result == null) {
			// TODO 预测 mbd 的类型
			Class<?> beanType = predictBeanType(beanName, mbd, FactoryBean.class);
			// TODO 看其是否为 FactoryBean 类型, 即 mbd 的 type 是否为 FactoryBean 本身, 或其子类型
			result = (beanType != null && FactoryBean.class.isAssignableFrom(beanType));
			mbd.isFactoryBean = result;
		}
		return result;
	}

	/**
	 * Determine the bean type for the given FactoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean
	 * already. The implementation is allowed to instantiate the target factory bean if
	 * {@code allowInit} is {@code true} and the type cannot be determined another way;
	 * otherwise it is restricted to introspecting signatures and related metadata.
	 * <p>If no {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} is set on the bean definition
	 * and {@code allowInit} is {@code true}, the default implementation will create
	 * the FactoryBean via {@code getBean} to call its {@code getObjectType} method.
	 * Subclasses are encouraged to optimize this, typically by inspecting the generic
	 * signature of the factory bean class or the factory method that creates it.
	 * If subclasses do instantiate the FactoryBean, they should consider trying the
	 * {@code getObjectType} method without fully populating the bean. If this fails,
	 * a full FactoryBean creation as performed by this implementation should be used
	 * as fallback.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param allowInit if initialization of the FactoryBean is permitted if the type
	 * cannot be determined another way
	 * @return the type for the bean if determinable, otherwise {@code ResolvableType.NONE}
	 * @since 5.2
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 */
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		try {
			// TODO 从mbd的'factoryBeanObjectType'属性中取得类型
			ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
			if (result != ResolvableType.NONE) {
				// TODO 不是NONE类型时, 直接返回
				return result;
			}
		}
		catch (IllegalArgumentException ex) {
			throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
					String.valueOf(ex.getMessage()));
		}

		if (allowInit && mbd.isSingleton()) {
			try {
				// TODO 在允许初始化, 且合并过的bd是单例的情况下, 取得工厂类, 因为是要取得工厂类, 而不是工厂中的对象, 所以这里加上了'&'
				FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
				// TODO 取得工厂类的类型
				Class<?> objectType = getTypeForFactoryBean(factoryBean);
				// TODO 有工厂类型时返回类型对应的class引用, 否则返回一个NONE类型
				return (objectType != null ? ResolvableType.forClass(objectType) : ResolvableType.NONE);
			}
			catch (BeanCreationException ex) {
				if (ex.contains(BeanCurrentlyInCreationException.class)) {
					logger.trace(LogMessage.format("Bean currently in creation on FactoryBean type check: %s", ex));
				}
				else if (mbd.isLazyInit()) {
					logger.trace(LogMessage.format("Bean creation exception on lazy FactoryBean type check: %s", ex));
				}
				else {
					logger.debug(LogMessage.format("Bean creation exception on eager FactoryBean type check: %s", ex));
				}
				onSuppressedException(ex);
			}
		}

		// FactoryBean type not resolvable
		// TODO 没解析出类型时, 返回NONE
		return ResolvableType.NONE;
	}

	/**
	 * Mark the specified bean as already created (or about to be created).
	 * <p>This allows the bean factory to optimize its caching for repeated
	 * creation of the specified bean.
	 * @param beanName the name of the bean
	 */
	protected void markBeanAsCreated(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			synchronized (this.mergedBeanDefinitions) {
				if (!isBeanEligibleForMetadataCaching(beanName)) {
					// Let the bean definition get re-merged now that we're actually creating
					// the bean... just in case some of its metadata changed in the meantime.
					clearMergedBeanDefinition(beanName);
				}
				this.alreadyCreated.add(beanName);
			}
		}
	}

	/**
	 * Perform appropriate cleanup of cached metadata after bean creation failed.
	 * @param beanName the name of the bean
	 */
	protected void cleanupAfterBeanCreationFailure(String beanName) {
		synchronized (this.mergedBeanDefinitions) {
			this.alreadyCreated.remove(beanName);
		}
	}

	/**
	 * Determine whether the specified bean is eligible for having
	 * its bean definition metadata cached.
	 * @param beanName the name of the bean
	 * @return {@code true} if the bean's metadata may be cached
	 * at this point already
	 */
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return this.alreadyCreated.contains(beanName);
	}

	/**
	 * Remove the singleton instance (if any) for the given bean name,
	 * but only if it hasn't been used for other purposes than type checking.
	 * @param beanName the name of the bean
	 * @return {@code true} if actually removed, {@code false} otherwise
	 */
	protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			removeSingleton(beanName);
			return true;
		}
		else {
			return false;
		}
	}

	/**
	 * Check whether this factory's bean creation phase already started,
	 * i.e. whether any bean has been marked as created in the meantime.
	 * @since 4.2.2
	 * @see #markBeanAsCreated
	 */
	protected boolean hasBeanCreationStarted() {
		return !this.alreadyCreated.isEmpty();
	}

	/**
	 * Get the object for the given bean instance, either the bean
	 * instance itself or its created object in case of a FactoryBean.
	 * @param beanInstance the shared bean instance 共享的单例bean实例, 有可能是个工厂类(FactoryBean)
	 * @param name the name that may include factory dereference prefix 想要得到的bean对象的名字, 工厂类会带有'&'前缀
	 * @param beanName the canonical bean name 容器内bean的映射名
	 * @param mbd the merged bean definition 合并过双亲属性的bd
	 * @return the object to expose for the bean 要暴露的bean
	 */
	protected Object getObjectForBeanInstance(Object beanInstance, @Nullable Class<?> requiredType,
			String name, String beanName, @Nullable RootBeanDefinition mbd) {

		// Don't let calling code try to dereference the factory if the bean isn't a factory.
		// TODO 判断一下要得到的 bean 是否为工厂类(工厂类的名字前会加上 '&' 表示其为 spring 管理的一个引用, 而非 bean)
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			// TODO 是工厂类的情况时, 即名字以 '&' 开头时, 处理一下 bean 实例
			if (beanInstance instanceof NullBean) {
				// TODO bean 实例如果是 NullBean, 则直接返回
				return beanInstance;
			}
			if (!(beanInstance instanceof FactoryBean)) {
				// TODO bean 实例不是 FactoryBean, 则抛出异常
				throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
			}
			if (mbd != null) {
				// TODO 如果传入了 mbd , 因为要得到的 bean 是工厂类, 所以 mbd 也应该与实例保持一致, 变为工厂类
				mbd.isFactoryBean = true;
			}
			// TODO bean 实例本身即为要取得的工厂类, 即 getBean("&xxx") 取得的是beanFactory
			return beanInstance;
		}

		// Now we have the bean instance, which may be a normal bean or a FactoryBean.
		// If it's a FactoryBean, we use it to create a bean instance, unless the
		// caller actually wants a reference to the factory.
		// TODO 走到这里表示要得到的bean并不是以'&'开头的工厂类, 这时就要从bean实例中取得对象. 这时bean实例有可能是一个普通的bean,
		//  也有可能是一个工厂类
		if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
			// TODO bean实例是普通类时, 直接返回实例本身
			return beanInstance;
		}
		// TODO 走到这里表示要得到的bean不是以'&'开头的工厂类, 但其实例是工厂类型(实现了FactoryBean), 下面就开始处理实例. 先创建一个工厂类
		Object object = null;
		if (mbd != null) {
			// TODO 如果传入了mbd, 因为要得到的bean的实例是工厂类型, 所以mbd也应该与实例保持一致, 变为工厂类
			mbd.isFactoryBean = true;
		}
		else {
			// TODO 没有传入mbd, 则尝试在factoryBeanObjectCache缓存取得指定的工厂类
			object = getCachedObjectForFactoryBean(beanName);
		}
		if (object == null) {
			// Return bean instance from factory.
			// Caches object obtained from FactoryBean if it is a singleton.
			// TODO 查看注册中心beanDefinitionMap缓存中是否包含对应的bean definition
			if (mbd == null && containsBeanDefinition(beanName)) {
				// TODO 没有mbd, 且注册中心包含对应的bean时, 取得bean对应的RootBeanDefinition(有双亲bd时会合并双亲bd的属性)
				mbd = getMergedLocalBeanDefinition(beanName);
			}
			// TODO 确定bean是否由程序本身定义
			//  1. true: 合并后的RootBeanDefinition不是由程序创建的, 不需要用后处理器进行处理
			//  2. false: 合并后的RootBeanDefinition是由程序创建的, 需要用后处理器进行处理
			boolean synthetic = (mbd != null && mbd.isSynthetic());
			// TODO 从实例工厂类中取得原始bean对象, 然后再根据bean是否由程序本身定义来决定是否对原始bean对象进行加工
			//  1. synthetic = true: 不是由程序创建的, 不需要用后处理器进行处理
			//  2. synthetic = false: 由程序创建的, 需要用后处理器进行处理
			object = getObjectFromFactoryBean(factoryBean, requiredType, beanName, !synthetic);
		}
		// TODO 返回的可能是一个原始bean对象, 也可能是一个用后处理器加工过的bean对象
		return object;
	}

	/**
	 * Determine whether the given bean name is already in use within this factory,
	 * i.e. whether there is a local bean or alias registered under this name or
	 * an inner bean created with this name.
	 * @param beanName the name to check
	 */
	public boolean isBeanNameInUse(String beanName) {
		return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
	}

	/**
	 * Determine whether the given bean requires destruction on shutdown.
	 * <p>The default implementation checks the DisposableBean interface as well as
	 * a specified destroy method and registered DestructionAwareBeanPostProcessors.
	 * @param bean the bean instance to check
	 * @param mbd the corresponding bean definition
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see AbstractBeanDefinition#getDestroyMethodName()
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
		return (bean.getClass() != NullBean.class && (DisposableBeanAdapter.hasDestroyMethod(bean, mbd) ||
				(hasDestructionAwareBeanPostProcessors() && DisposableBeanAdapter.hasApplicableProcessors(
						bean, getBeanPostProcessorCache().destructionAware))));
	}

	/**
	 * Add the given bean to the list of disposable beans in this factory,
	 * registering its DisposableBean interface and/or the given destroy method
	 * to be called on factory shutdown (if applicable). Only applies to singletons.
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 * @param mbd the bean definition for the bean
	 * @see RootBeanDefinition#isSingleton
	 * @see RootBeanDefinition#getDependsOn
	 * @see #registerDisposableBean
	 * @see #registerDependentBean
	 */
	protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
		if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
			if (mbd.isSingleton()) {
				// Register a DisposableBean implementation that performs all destruction
				// work for the given bean: DestructionAwareBeanPostProcessors,
				// DisposableBean interface, custom destroy method.
				registerDisposableBean(beanName, new DisposableBeanAdapter(
						bean, beanName, mbd, getBeanPostProcessorCache().destructionAware));
			}
			else {
				// A bean with a custom scope...
				Scope scope = this.scopes.get(mbd.getScope());
				if (scope == null) {
					throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
				}
				scope.registerDestructionCallback(beanName, new DisposableBeanAdapter(
						bean, beanName, mbd, getBeanPostProcessorCache().destructionAware));
			}
		}
	}


	//---------------------------------------------------------------------
	// Abstract methods to be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Check if this bean factory contains a bean definition with the given name.
	 * Does not consider any hierarchy this factory may participate in.
	 * Invoked by {@code containsBean} when no cached singleton instance is found.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * @param beanName the name of the bean to look for
	 * @return if this bean factory contains a bean definition with the given name
	 * @see #containsBean
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 */
	protected abstract boolean containsBeanDefinition(String beanName);

	/**
	 * Return the bean definition for the given bean name.
	 * Subclasses should normally implement caching, as this method is invoked
	 * by this class every time bean definition metadata is needed.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * @param beanName the name of the bean to find a definition for
	 * @return the BeanDefinition for this prototype name (never {@code null})
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException
	 * if the bean definition cannot be resolved
	 * @throws BeansException in case of errors
	 * @see RootBeanDefinition
	 * @see ChildBeanDefinition
	 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
	 */
	protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

	/**
	 * Create a bean instance for the given merged bean definition (and arguments).
	 * The bean definition will already have been merged with the parent definition
	 * in case of a child definition.
	 * <p>All bean retrieval methods delegate to this method for actual bean creation.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 */
	protected abstract Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object @Nullable [] args)
			throws BeanCreationException;


	/**
	 * CopyOnWriteArrayList which resets the beanPostProcessorCache field on modification.
	 *
	 * @since 5.3
	 */
	@SuppressWarnings("serial")
	private class BeanPostProcessorCacheAwareList extends CopyOnWriteArrayList<BeanPostProcessor> {

		@Override
		public BeanPostProcessor set(int index, BeanPostProcessor element) {
			BeanPostProcessor result = super.set(index, element);
			resetBeanPostProcessorCache();
			return result;
		}

		@Override
		public boolean add(BeanPostProcessor o) {
			boolean success = super.add(o);
			resetBeanPostProcessorCache();
			return success;
		}

		@Override
		public void add(int index, BeanPostProcessor element) {
			super.add(index, element);
			resetBeanPostProcessorCache();
		}

		@Override
		public BeanPostProcessor remove(int index) {
			BeanPostProcessor result = super.remove(index);
			resetBeanPostProcessorCache();
			return result;
		}

		@Override
		public boolean remove(Object o) {
			boolean success = super.remove(o);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			boolean success = super.removeAll(c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			boolean success = super.retainAll(c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean addAll(Collection<? extends BeanPostProcessor> c) {
			boolean success = super.addAll(c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean addAll(int index, Collection<? extends BeanPostProcessor> c) {
			boolean success = super.addAll(index, c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean removeIf(Predicate<? super BeanPostProcessor> filter) {
			boolean success = super.removeIf(filter);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public void replaceAll(UnaryOperator<BeanPostProcessor> operator) {
			super.replaceAll(operator);
			resetBeanPostProcessorCache();
		}
	}


	/**
	 * Internal cache of pre-filtered post-processors.
	 *
	 * @since 5.3
	 */
	static class BeanPostProcessorCache {

		final List<InstantiationAwareBeanPostProcessor> instantiationAware = new ArrayList<>();

		final List<SmartInstantiationAwareBeanPostProcessor> smartInstantiationAware = new ArrayList<>();

		final List<DestructionAwareBeanPostProcessor> destructionAware = new ArrayList<>();

		final List<MergedBeanDefinitionPostProcessor> mergedDefinition = new ArrayList<>();
	}


	/**
	 * {@link PropertyEditorRegistrar} that delegates to the bean factory's
	 * default registrars, adding exception handling for circular reference
	 * scenarios where an editor tries to refer back to the currently created bean.
	 *
	 * @since 6.2.3
	 */
	class BeanFactoryDefaultEditorRegistrar implements PropertyEditorRegistrar {

		@Override
		public void registerCustomEditors(PropertyEditorRegistry registry) {
			applyEditorRegistrars(registry, defaultEditorRegistrars);
		}
	}

}
