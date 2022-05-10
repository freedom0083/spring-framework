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

package org.springframework.aop.framework.autoproxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.aopalliance.aop.Advice;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.ProxyProcessorSupport;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that wraps each eligible bean with an AOP proxy, delegating to specified interceptors
 * before invoking the bean itself.
 *
 * <p>This class distinguishes between "common" interceptors: shared for all proxies it
 * creates, and "specific" interceptors: unique per bean instance. There need not be any
 * common interceptors. If there are, they are set using the interceptorNames property.
 * As with {@link org.springframework.aop.framework.ProxyFactoryBean}, interceptors names
 * in the current factory are used rather than bean references to allow correct handling
 * of prototype advisors and interceptors: for example, to support stateful mixins.
 * Any advice type is supported for {@link #setInterceptorNames "interceptorNames"} entries.
 *
 * <p>Such auto-proxying is particularly useful if there's a large number of beans that
 * need to be wrapped with similar proxies, i.e. delegating to the same interceptors.
 * Instead of x repetitive proxy definitions for x target beans, you can register
 * one single such post processor with the bean factory to achieve the same effect.
 *
 * <p>Subclasses can apply any strategy to decide if a bean is to be proxied, e.g. by type,
 * by name, by definition details, etc. They can also return additional interceptors that
 * should just be applied to the specific bean instance. A simple concrete implementation is
 * {@link BeanNameAutoProxyCreator}, identifying the beans to be proxied via given names.
 *
 * <p>Any number of {@link TargetSourceCreator} implementations can be used to create
 * a custom target source: for example, to pool prototype objects. Auto-proxying will
 * occur even if there is no advice, as long as a TargetSourceCreator specifies a custom
 * {@link org.springframework.aop.TargetSource}. If there are no TargetSourceCreators set,
 * or if none matches, a {@link org.springframework.aop.target.SingletonTargetSource}
 * will be used by default to wrap the target bean instance.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Sam Brannen
 * @since 13.10.2003
 * @see #setInterceptorNames
 * @see #getAdvicesAndAdvisorsForBean
 * @see BeanNameAutoProxyCreator
 * @see DefaultAdvisorAutoProxyCreator
 */
@SuppressWarnings("serial")
public abstract class AbstractAutoProxyCreator extends ProxyProcessorSupport
		implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

	/**
	 * Convenience constant for subclasses: Return value for "do not proxy".
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Nullable
	protected static final Object[] DO_NOT_PROXY = null;

	/**
	 * Convenience constant for subclasses: Return value for
	 * "proxy without additional interceptors, just the common ones".
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	protected static final Object[] PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS = new Object[0];


	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Default is global AdvisorAdapterRegistry. */
	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	/**
	 * Indicates whether or not the proxy should be frozen. Overridden from super
	 * to prevent the configuration from becoming frozen too early.
	 */
	// TODO 代理的配置是否被冻结, 即, 不能再改变
	private boolean freezeProxy = false;
	/** Default is no common interceptors. */
	// TODO 拦截器名集合. 默认没有通用拦截器
	private String[] interceptorNames = new String[0];
	// TODO 用来标识是否把通用拦截器放到拦截器列表的前面
	private boolean applyCommonInterceptorsFirst = true;
	// TODO 当配置了customTargetSourceCreators时, 会在后处理器的postProcessBeforeInstantiation()方法中进行创建代理类
	@Nullable
	private TargetSourceCreator[] customTargetSourceCreators;

	@Nullable
	private BeanFactory beanFactory;
	// TODO 代理目标源缓存. 后处理器在bean的实例化前postProcessBeforeInstantiation()方法会检查是否有自定义的目标源. 如果有的话,
	//  会为bean创建一个代理. 然后当前bean会做为代理bean的目标源, 将其名字存到这个缓存里. 只要进入这个缓存, 就说明bean已经被代理了
	private final Set<String> targetSourcedBeans = Collections.newSetFromMap(new ConcurrentHashMap<>(16));
	// TODO 提前暴露的代理bean缓存. 用于解析循环引用问题. 在创建bean时, 对于单例且支持提前暴露的bean会被加到这个缓存中, 然后再为其创建
	//  一个代理. 而后处理器在bean初始化的后处理过程中, postProcessAfterInitialization()方法也会通过判断bean是否在这个缓存中来决
	//  定是否对目标进行动态代理
	private final Map<Object, Object> earlyProxyReferences = new ConcurrentHashMap<>(16);
	// TODO bean生成的代理类的类型
	private final Map<Object, Class<?>> proxyTypes = new ConcurrentHashMap<>(16);
	// TODO 存储的是bean是否应该被代理(TRUE|FALSE)
	private final Map<Object, Boolean> advisedBeans = new ConcurrentHashMap<>(256);


	/**
	 * Set whether or not the proxy should be frozen, preventing advice
	 * from being added to it once it is created.
	 * <p>Overridden from the super class to prevent the proxy configuration
	 * from being frozen before the proxy is created.
	 */
	@Override
	public void setFrozen(boolean frozen) {
		this.freezeProxy = frozen;
	}

	@Override
	public boolean isFrozen() {
		return this.freezeProxy;
	}

	/**
	 * Specify the {@link AdvisorAdapterRegistry} to use.
	 * <p>Default is the global {@link AdvisorAdapterRegistry}.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	/**
	 * Set custom {@code TargetSourceCreators} to be applied in this order.
	 * If the list is empty, or they all return null, a {@link SingletonTargetSource}
	 * will be created for each bean.
	 * <p>Note that TargetSourceCreators will kick in even for target beans
	 * where no advices or advisors have been found. If a {@code TargetSourceCreator}
	 * returns a {@link TargetSource} for a specific bean, that bean will be proxied
	 * in any case.
	 * <p>{@code TargetSourceCreators} can only be invoked if this post processor is used
	 * in a {@link BeanFactory} and its {@link BeanFactoryAware} callback is triggered.
	 * @param targetSourceCreators the list of {@code TargetSourceCreators}.
	 * Ordering is significant: The {@code TargetSource} returned from the first matching
	 * {@code TargetSourceCreator} (that is, the first that returns non-null) will be used.
	 */
	public void setCustomTargetSourceCreators(TargetSourceCreator... targetSourceCreators) {
		this.customTargetSourceCreators = targetSourceCreators;
	}

	/**
	 * Set the common interceptors. These must be bean names in the current factory.
	 * They can be of any advice or advisor type Spring supports.
	 * <p>If this property isn't set, there will be zero common interceptors.
	 * This is perfectly valid, if "specific" interceptors such as matching
	 * Advisors are all we want.
	 */
	public void setInterceptorNames(String... interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * Set whether the common interceptors should be applied before bean-specific ones.
	 * Default is "true"; else, bean-specific interceptors will get applied first.
	 */
	public void setApplyCommonInterceptorsFirst(boolean applyCommonInterceptorsFirst) {
		this.applyCommonInterceptorsFirst = applyCommonInterceptorsFirst;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Return the owning {@link BeanFactory}.
	 * May be {@code null}, as this post-processor doesn't need to belong to a bean factory.
	 */
	@Nullable
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	// TODO 因为当前类是用于为bean创建代理的, 所以对于bean类型的预测就转化为对bean的代理类型的比较了
	@Override
	@Nullable
	public Class<?> predictBeanType(Class<?> beanClass, String beanName) {
		if (this.proxyTypes.isEmpty()) {
			return null;
		}
		Object cacheKey = getCacheKey(beanClass, beanName);
		return this.proxyTypes.get(cacheKey);
	}

	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		return null;
	}

	@Override
	public Object getEarlyBeanReference(Object bean, String beanName) {
		// TODO 为当前操作的bean创建一个用于缓存的key. 如果bean是个工厂类, 则为其加上'&'前缀, 否则就直接是当前操作的bean的名字.
		//  如果没有得到bean名的话, 那这个key就是当前操作的bean的class了
		Object cacheKey = getCacheKey(bean.getClass(), beanName);
		// TODO 放到提前暴露的bean的代理缓存中
		this.earlyProxyReferences.put(cacheKey, bean);
		// TODO 这里会看一下是否要为bean创建AOP代理. 如果容器有Advisor(如果支持AspectJ, @Aspect切面中的@Around, @Before, @After,
		//  @AfterReturning, @AfterThrowing这些Advice方法, 以及@DeclareParents字段也会被创建成Advisor), 且可以应用于bean时,
		//  会为bean生成代理
		return wrapIfNecessary(bean, beanName, cacheKey);
	}

	// TODO 后处理器在实例化前需要做的事情
	@Override
	public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
		// TODO 为当前操作的bean创建一个用于缓存的key. 如果bean是个工厂类, 则为其加上'&'前缀, 否则就直接是当前操作的bean的名字.
		//  如果没有得到bean名的话, 那这个key就是当前操作的bean的class了
		Object cacheKey = getCacheKey(beanClass, beanName);

		if (!StringUtils.hasLength(beanName) || !this.targetSourcedBeans.contains(beanName)) {
			// TODO 当前操作的bean没有名字时, 或者bean还没有创建过代理时(目标源缓存里没有就表示没创建过代理), 有可能不需要进行代理
			if (this.advisedBeans.containsKey(cacheKey)) {
				// TODO advisedBean缓存里存的是bean是否应该被代理的情况. 如果缓存里已经有了当前操作的bean, 就表示这个bean已经进行
				//  过代理创建步骤. 不用管其是否应该被代理. 这时就不需要再进行创建代理的动作了, 所以返回的就是一个null
				return null;
			}
			if (isInfrastructureClass(beanClass) || shouldSkip(beanClass, beanName)) {
				// TODO 对于基础设施bean, 或者指明需要跳过的bean, 也不需要创建代理, 所以放进缓存时给的值是False, 表示无需创建代理
				//  然后也返回null
				this.advisedBeans.put(cacheKey, Boolean.FALSE);
				return null;
			}
		}

		// Create proxy here if we have a custom TargetSource.
		// Suppresses unnecessary default instantiation of the target bean:
		// The TargetSource will handle target instances in a custom fashion.
		// TODO 其他情况, 就要看是否有自定义的目标源
		TargetSource targetSource = getCustomTargetSource(beanClass, beanName);
		if (targetSource != null) {
			// TODO 如果有自定义的目标源, 则设置其属性
			if (StringUtils.hasLength(beanName)) {
				// TODO 将当前操作的bean的名字添加到代理目标源缓存
				this.targetSourcedBeans.add(beanName);
			}
			// TODO 取得容器中所有的Advisor和Advice. 有两个实现:
			//  1. AbstractAdvisorAutoProxyCreator: 抽象类. 会取得容器中所有的Advisor(会对没有实例化的Advisor进行实例化操作).
			//     如果支持AspectJ, 则还会为@Aspect切面中的@Around, @Before, @After, @AfterReturning, @AfterThrowing方法,
			//     以及@DeclareParents字段创建Advisor
			//  2. BeanNameAutoProxyCreator: AbstractAutoProxyCreator的子类. 直接从配置好的列表里进行匹配. 只会对工厂类(FactoryBean)
			//     创建代理. 支持通配符
			Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource);
			// TODO 使用上面取得的Advisor(包括被包装成Advisor的Advice方法), 为bean生成代理
			Object proxy = createProxy(beanClass, beanName, specificInterceptors, targetSource);
			// TODO 然后将生成的代理保存到缓存中返回
			this.proxyTypes.put(cacheKey, proxy.getClass());
			return proxy;
		}
		// TODO 如果没有自定义的目标源, 也不需要创建代理, 直接返回null
		return null;
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		return pvs;  // skip postProcessPropertyValues
	}

	/**
	 * Create a proxy with the configured interceptors if the bean is
	 * identified as one to proxy by the subclass.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Override
	public Object postProcessAfterInitialization(@Nullable Object bean, String beanName) {
		if (bean != null) {
			Object cacheKey = getCacheKey(bean.getClass(), beanName);
			if (this.earlyProxyReferences.remove(cacheKey) != bean) {
				return wrapIfNecessary(bean, beanName, cacheKey);
			}
		}
		return bean;
	}


	/**
	 * Build a cache key for the given bean class and bean name.
	 * <p>Note: As of 4.2.3, this implementation does not return a concatenated
	 * class/name String anymore but rather the most efficient cache key possible:
	 * a plain bean name, prepended with {@link BeanFactory#FACTORY_BEAN_PREFIX}
	 * in case of a {@code FactoryBean}; or if no bean name specified, then the
	 * given bean {@code Class} as-is.
	 * @param beanClass the bean class
	 * @param beanName the bean name
	 * @return the cache key for the given class and name
	 */
	protected Object getCacheKey(Class<?> beanClass, @Nullable String beanName) {
		if (StringUtils.hasLength(beanName)) {
			// TODO 提供了名字时, 如果当前的bean是个工厂类, 则为其加上'&'前缀
			return (FactoryBean.class.isAssignableFrom(beanClass) ?
					BeanFactory.FACTORY_BEAN_PREFIX + beanName : beanName);
		}
		else {
			return beanClass;
		}
	}

	/**
	 * Wrap the given bean if necessary, i.e. if it is eligible for being proxied.
	 * @param bean the raw bean instance 要创建的bean的原始实例
	 * @param beanName the name of the bean 要创建的bean的名字
	 * @param cacheKey the cache key for metadata access
	 * @return a proxy wrapping the bean, or the raw bean instance as-is
	 */
	// TODO 容器有Advisor(如果支持AspectJ, @Aspect切面中的@Around, @Before, @After, @AfterReturning, @AfterThrowing这些Advice
	//  方法, 以及@DeclareParents字段也会被创建成Advisor)时, 会为bean生成代理(对于Advice方法包装成的Advisor, 还要判断这些Advisor
	//  是否可以应用于当前操作bean, 只有适用时才会为其生成代理).
	//  如果没有, 或者bean不需要代理, 或者bean是基础设施类时, 不会为bean创建代理, 直接返回
	protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
		if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
			// TODO 如果当前操作的bean存在于缓存中, 无需进行处理, 直接返回即可
			return bean;
		}
		if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
			// TODO 对于不需要代理的bean, 直接返回即可
			return bean;
		}
		if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
			// TODO 所有的基础设施类(Advice, Pointcut, Advisor, AopInfrastructureBean), 或者当前bean是以'.ORIGINAL'结尾的原始
			//  bean时, 也是不需要进行代理的, 也直接返回
			this.advisedBeans.put(cacheKey, Boolean.FALSE);
			return bean;
		}

		// Create proxy if we have advice.
		// TODO 其他的就是可能需要进行AOP代理的bean了. 首先取得容器中所有的Advisor和Advice通知. 有两个实现:
		//  1. AbstractAdvisorAutoProxyCreator: 抽象类. 会取得容器中所有的Advisor(会对没有实例化的Advisor进行实例化操作).
		//     如果支持AspectJ, 则还会为@Aspect切面中的@Around, @Before, @After, @AfterReturning, @AfterThrowing方法,
		//     以及@DeclareParents字段创建Advisor
		//  2. BeanNameAutoProxyCreator: AbstractAutoProxyCreator的子类. 直接从配置好的列表里进行匹配. 只会对工厂类(FactoryBean)
		//     创建代理. 支持通配符
		Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
		if (specificInterceptors != DO_NOT_PROXY) {
			// TODO 找到了Advisor时, 准备为bean做代理. 先设置缓存, 说明bean需要被代理
			this.advisedBeans.put(cacheKey, Boolean.TRUE);
			// TODO 使用上面取得的Advisor(包括被包装成Advisor的Advice方法), 为bean生成代理
			Object proxy = createProxy(
					bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
			// TODO 创建好的代理放到代理类型缓存后返回
			this.proxyTypes.put(cacheKey, proxy.getClass());
			return proxy;
		}
		// TODO 需要代理的bean上面已经全都返回了, 剩下的就是不需要的了
		this.advisedBeans.put(cacheKey, Boolean.FALSE);
		return bean;
	}

	/**
	 * Return whether the given bean class represents an infrastructure class
	 * that should never be proxied.
	 * <p>The default implementation considers Advices, Advisors and
	 * AopInfrastructureBeans as infrastructure classes.
	 * @param beanClass the class of the bean
	 * @return whether the bean represents an infrastructure class
	 * @see org.aopalliance.aop.Advice
	 * @see org.springframework.aop.Advisor
	 * @see org.springframework.aop.framework.AopInfrastructureBean
	 * @see #shouldSkip
	 */
	// TODO Advice, Pointcut, Advisor, AopInfrastructureBean都是AOP要用的基础设施类, 不需要进行代理
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		boolean retVal = Advice.class.isAssignableFrom(beanClass) ||
				Pointcut.class.isAssignableFrom(beanClass) ||
				Advisor.class.isAssignableFrom(beanClass) ||
				AopInfrastructureBean.class.isAssignableFrom(beanClass);
		if (retVal && logger.isTraceEnabled()) {
			logger.trace("Did not attempt to auto-proxy infrastructure class [" + beanClass.getName() + "]");
		}
		return retVal;
	}

	/**
	 * Subclasses should override this method to return {@code true} if the
	 * given bean should not be considered for auto-proxying by this post-processor.
	 * <p>Sometimes we need to be able to avoid this happening, e.g. if it will lead to
	 * a circular reference or if the existing target instance needs to be preserved.
	 * This implementation returns {@code false} unless the bean name indicates an
	 * "original instance" according to {@code AutowireCapableBeanFactory} conventions.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether to skip the given bean
	 * @see org.springframework.beans.factory.config.AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX
	 */
	// TODO 标识出要跳过的bean. 如果后处理器不需要对bean进行自动代理操作, 则直接返回true即可.
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		// TODO 跳过所有原始bean实例
		return AutoProxyUtils.isOriginalInstance(beanName, beanClass);
	}

	/**
	 * Create a target source for bean instances. Uses any TargetSourceCreators if set.
	 * Returns {@code null} if no custom TargetSource should be used.
	 * <p>This implementation uses the "customTargetSourceCreators" property.
	 * Subclasses can override this method to use a different mechanism.
	 * @param beanClass the class of the bean to create a TargetSource for
	 * @param beanName the name of the bean
	 * @return a TargetSource for this bean
	 * @see #setCustomTargetSourceCreators
	 */
	@Nullable
	protected TargetSource getCustomTargetSource(Class<?> beanClass, String beanName) {
		// We can't create fancy target sources for directly registered singletons.
		if (this.customTargetSourceCreators != null &&
				this.beanFactory != null && this.beanFactory.containsBean(beanName)) {
			// TODO 如果有自定义的TargetSourceCreator, 且当前操作的bean已经存在于容器中时
			for (TargetSourceCreator tsc : this.customTargetSourceCreators) {
				// TODO 遍历所有自定义的TargetSourceCreator, 尝试用其中一个Creator取得当前操作bean的TargetSource
				TargetSource ts = tsc.getTargetSource(beanClass, beanName);
				if (ts != null) {
					// Found a matching TargetSource.
					if (logger.isTraceEnabled()) {
						logger.trace("TargetSourceCreator [" + tsc +
								"] found custom TargetSource for bean with name '" + beanName + "'");
					}
					// TODO 断路操作, 只要有一个取得了TargetSource, 退出
					return ts;
				}
			}
		}

		// No custom TargetSource found.
		return null;
	}

	/**
	 * Create an AOP proxy for the given bean.
	 * @param beanClass the class of the bean 要创建代理的bean的class
	 * @param beanName the name of the bean 要创建代理的bean的名字
	 * @param specificInterceptors the set of interceptors that is specific to this bean (may be empty, but not null) 这
	 *                             里表示的是方法拦截器, 其实放的是Advisor
	 * @param targetSource the TargetSource for the proxy, already pre-configured to access the bean 代理的目标源
	 * @return the AOP proxy for the bean
	 * @see #buildAdvisors
	 */
	// TODO 为当前操作的bean创建代理
	protected Object createProxy(Class<?> beanClass, @Nullable String beanName,
			@Nullable Object[] specificInterceptors, TargetSource targetSource) {

		if (this.beanFactory instanceof ConfigurableListableBeanFactory) {
			// TODO 给当前bean设置'AutoProxyUtils.originalTargetClass'属性
			AutoProxyUtils.exposeTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName, beanClass);
		}
		// TODO 创建一个代理工厂, 其为ProxyCreatorSupport的子类
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.copyFrom(this);

		if (proxyFactory.isProxyTargetClass()) {
			// Explicit handling of JDK proxy targets and lambdas (for introduction advice scenarios)
			if (Proxy.isProxyClass(beanClass) || ClassUtils.isLambdaClass(beanClass)) {
				// Must allow for introductions; can't just set interfaces to the proxy's interfaces only.
				for (Class<?> ifc : beanClass.getInterfaces()) {
					proxyFactory.addInterface(ifc);
				}
			}
		}
		else {
			// No proxyTargetClass flag enforced, let's apply our default checks...
			if (shouldProxyTargetClass(beanClass, beanName)) {
				// TODO 这里会看正在创建的bean的preserveTargetClass属性是否为true. 如果为true, 会强制使用CGLIB来创建代理
				proxyFactory.setProxyTargetClass(true);
			}
			else {
				// TODO 如果preserveTargetClass属性不是true, 或者没有时, 再看一下目标类的接口. 如果目标类的接口不是以下这些回调接口
				//  InitializingBean, DisposableBean, Closeable, AutoCloseable, Aware.class, 也不是以下这些内部用的,
				//  'groovy.lang.GroovyObject, 以'.cglib.proxy.Factory或者'.bytebuddy.MockAccess'结尾的class, 就表示其为一个
				//  接口代理. 目标类所实现的所有接口都会加到代理工厂中. 否则, 表示还是需要应用CGLIB来进行代理创建
				evaluateProxyInterfaces(beanClass, proxyFactory);
			}
		}
		// TODO 创建所有的Advisor. 设置的通用拦截器会被转化为Advisor, 并且默认情况下会排在其他拦截器前. 经过这个方法后, Advice就会
		//  被包装为Advisor了
		Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
		// TODO 然后设置代理工厂的Advisor和目标源设置
		proxyFactory.addAdvisors(advisors);
		proxyFactory.setTargetSource(targetSource);
		// TODO 勾子方法, 用于子类对代理目标进行特殊设置. 目前没有实现
		customizeProxyFactory(proxyFactory);
		// TODO 设置代理工厂中的Advice是否冻结
		proxyFactory.setFrozen(this.freezeProxy);
		if (advisorsPreFiltered()) {
			// TODO Advisor是否被提前过滤过. 当前类返回的是False; AbstractAdvisorAutoProxyCreator重写了该方法, 返回True
			//  如果Advisor被提前过滤过, 代理工厂也做同样的设置
			proxyFactory.setPreFiltered(true);
		}

		// Use original ClassLoader if bean class not locally loaded in overriding class loader
		ClassLoader classLoader = getProxyClassLoader();
		if (classLoader instanceof SmartClassLoader && classLoader != beanClass.getClassLoader()) {
			classLoader = ((SmartClassLoader) classLoader).getOriginalClassLoader();
		}
		// TODO 创建代理并返回
		return proxyFactory.getProxy(classLoader);
	}

	/**
	 * Determine whether the given bean should be proxied with its target class rather than its interfaces.
	 * <p>Checks the {@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE "preserveTargetClass" attribute}
	 * of the corresponding bean definition.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether the given bean should be proxied with its target class
	 * @see AutoProxyUtils#shouldProxyTargetClass
	 */
	// TODO 判断是为类, 还是为接口创建代理. 检查的是要创建的bean的preserveTargetClass属性是否为true
	protected boolean shouldProxyTargetClass(Class<?> beanClass, @Nullable String beanName) {
		return (this.beanFactory instanceof ConfigurableListableBeanFactory &&
				AutoProxyUtils.shouldProxyTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName));
	}

	/**
	 * Return whether the Advisors returned by the subclass are pre-filtered
	 * to match the bean's target class already, allowing the ClassFilter check
	 * to be skipped when building advisors chains for AOP invocations.
	 * <p>Default is {@code false}. Subclasses may override this if they
	 * will always return pre-filtered Advisors.
	 * @return whether the Advisors are pre-filtered
	 * @see #getAdvicesAndAdvisorsForBean
	 * @see org.springframework.aop.framework.Advised#setPreFiltered
	 */
	protected boolean advisorsPreFiltered() {
		return false;
	}

	/**
	 * Determine the advisors for the given bean, including the specific interceptors
	 * as well as the common interceptor, all adapted to the Advisor interface.
	 * @param beanName the name of the bean
	 * @param specificInterceptors the set of interceptors that is specific to this bean (may be empty, but not null)
	 * @return the list of Advisors for the given bean
	 */
	// TODO 用来把interceptorNames设置的拦截器解析成MethodInterceptor, 以及Advice, 然后再将他们包装成Advisor
	protected Advisor[] buildAdvisors(@Nullable String beanName, @Nullable Object[] specificInterceptors) {
		// Handle prototypes correctly...
		// TODO 先处理通用的拦截器, 全部包装为Advisor
		Advisor[] commonInterceptors = resolveInterceptorNames();

		List<Object> allInterceptors = new ArrayList<>();
		if (specificInterceptors != null) {
			if (specificInterceptors.length > 0) {
				// specificInterceptors may equal PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
				allInterceptors.addAll(Arrays.asList(specificInterceptors));
			}
			// TODO 然后再处理由Advice通知创建的Advisor. 默认情况下, 通用的拦截器创建的Advisor排在Advice通知创建的Advisor之前
			if (commonInterceptors.length > 0) {
				if (this.applyCommonInterceptorsFirst) {
					allInterceptors.addAll(0, Arrays.asList(commonInterceptors));
				}
				else {
					allInterceptors.addAll(Arrays.asList(commonInterceptors));
				}
			}
		}
		if (logger.isTraceEnabled()) {
			int nrOfCommonInterceptors = commonInterceptors.length;
			int nrOfSpecificInterceptors = (specificInterceptors != null ? specificInterceptors.length : 0);
			logger.trace("Creating implicit proxy for bean '" + beanName + "' with " + nrOfCommonInterceptors +
					" common interceptors and " + nrOfSpecificInterceptors + " specific interceptors");
		}

		Advisor[] advisors = new Advisor[allInterceptors.size()];
		for (int i = 0; i < allInterceptors.size(); i++) {
			// TODO 再包装一次. 这时处理的主要是Advice方法
			advisors[i] = this.advisorAdapterRegistry.wrap(allInterceptors.get(i));
		}
		return advisors;
	}

	/**
	 * Resolves the specified interceptor names to Advisor objects.
	 * @see #setInterceptorNames
	 */
	private Advisor[] resolveInterceptorNames() {
		BeanFactory bf = this.beanFactory;
		ConfigurableBeanFactory cbf = (bf instanceof ConfigurableBeanFactory ? (ConfigurableBeanFactory) bf : null);
		List<Advisor> advisors = new ArrayList<>();
		for (String beanName : this.interceptorNames) {
			if (cbf == null || !cbf.isCurrentlyInCreation(beanName)) {
				Assert.state(bf != null, "BeanFactory required for resolving interceptor names");
				// TODO 挨个实例化拦截器
				Object next = bf.getBean(beanName);
				// TODO 所有的拦截器都会被包装成Advisor
				advisors.add(this.advisorAdapterRegistry.wrap(next));
			}
		}
		return advisors.toArray(new Advisor[0]);
	}

	/**
	 * Subclasses may choose to implement this: for example,
	 * to change the interfaces exposed.
	 * <p>The default implementation is empty.
	 * @param proxyFactory a ProxyFactory that is already configured with
	 * TargetSource and interfaces and will be used to create the proxy
	 * immediately after this method returns
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}


	/**
	 * Return whether the given bean is to be proxied, what additional
	 * advices (e.g. AOP Alliance interceptors) and advisors to apply.
	 * @param beanClass the class of the bean to advise
	 * @param beanName the name of the bean
	 * @param customTargetSource the TargetSource returned by the
	 * {@link #getCustomTargetSource} method: may be ignored.
	 * Will be {@code null} if no custom target source is in use.
	 * @return an array of additional interceptors for the particular bean;
	 * or an empty array if no additional interceptors but just the common ones;
	 * or {@code null} if no proxy at all, not even with the common interceptors.
	 * See constants DO_NOT_PROXY and PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS.
	 * @throws BeansException in case of errors
	 * @see #DO_NOT_PROXY
	 * @see #PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
	 */
	@Nullable
	protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName,
			@Nullable TargetSource customTargetSource) throws BeansException;

}
