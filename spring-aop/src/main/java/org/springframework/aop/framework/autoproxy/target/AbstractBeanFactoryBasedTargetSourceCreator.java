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

package org.springframework.aop.framework.autoproxy.target;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.autoproxy.TargetSourceCreator;
import org.springframework.aop.target.AbstractBeanFactoryBasedTargetSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.lang.Nullable;

/**
 * Convenient superclass for
 * {@link org.springframework.aop.framework.autoproxy.TargetSourceCreator}
 * implementations that require creating multiple instances of a prototype bean.
 *
 * <p>Uses an internal BeanFactory to manage the target instances,
 * copying the original bean definition to this internal factory.
 * This is necessary because the original BeanFactory will just
 * contain the proxy instance created through auto-proxying.
 *
 * <p>Requires running in an
 * {@link org.springframework.beans.factory.support.AbstractBeanFactory}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.aop.target.AbstractBeanFactoryBasedTargetSource
 * @see org.springframework.beans.factory.support.AbstractBeanFactory
 */
public abstract class AbstractBeanFactoryBasedTargetSourceCreator
		implements TargetSourceCreator, BeanFactoryAware, DisposableBean {

	protected final Log logger = LogFactory.getLog(getClass());

	private ConfigurableBeanFactory beanFactory;

	/** Internally used DefaultListableBeanFactory instances, keyed by bean name. */
	private final Map<String, DefaultListableBeanFactory> internalBeanFactories =
			new HashMap<>();


	@Override
	public final void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableBeanFactory)) {
			throw new IllegalStateException("Cannot do auto-TargetSource creation with a BeanFactory " +
					"that doesn't implement ConfigurableBeanFactory: " + beanFactory.getClass());
		}
		this.beanFactory = (ConfigurableBeanFactory) beanFactory;
	}

	/**
	 * Return the BeanFactory that this TargetSourceCreators runs in.
	 */
	protected final BeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	//---------------------------------------------------------------------
	// Implementation of the TargetSourceCreator interface
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public final TargetSource getTargetSource(Class<?> beanClass, String beanName) {
		// TODO 根据当前操作的bean来创建一个TargetSource. 一共有两个实现覆盖了创建目标源的方法:
		//  1. LazyInitTargetSourceCreator: 用于延迟初始化. 如果bean设置了延迟加载, 则会创建一个LazyInitTargetSource目标源,
		//     此目标源只有在调用getTarget()方法时才会创建目标源.
		//  2. QuickTargetSourceCreator: 根据当前操作的bean的名字来创建目标源.
		//     a. CommonsPool2TargetSource: 以':'开头的目标源池, 设置了最大容器为25, 具体实现是委托给了GenericObjectPoolConfig;
		//     b. ThreadLocalTargetSource: 以'%'开头的ThreadLocal类型的目标源. 每个线程都会绑定自己的目标源, 这些目标源同样也是prototype的;
		//     c. PrototypeTargetSource: 以'!'开头的原型类型的目标源, 其生成的目标源不是单例的了;
		//     d. 其他情况返回的都是null
		AbstractBeanFactoryBasedTargetSource targetSource =
				createBeanFactoryBasedTargetSource(beanClass, beanName);
		if (targetSource == null) {
			return null;
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Configuring AbstractBeanFactoryBasedTargetSource: " + targetSource);
		}
		// TODO 取得用于处理当前bean的内部容器
		DefaultListableBeanFactory internalBeanFactory = getInternalBeanFactoryForBean(beanName);

		// We need to override just this bean definition, as it may reference other beans
		// and we're happy to take the parent's definition for those.
		// Always use prototype scope if demanded.
		BeanDefinition bd = this.beanFactory.getMergedBeanDefinition(beanName);
		// TODO 复制当前bean的mbd, 创建一个新的GenericBeanDefinition
		GenericBeanDefinition bdCopy = new GenericBeanDefinition(bd);
		if (isPrototypeBased()) {
			// TODO 根据当前目标源构造器来设置mbd的scope:
			//  1. AbstractBeanFactoryBasedTargetSourceCreator: 默认为true. 即, 所有的目标源全是prototype的
			//  2. LazyInitTargetSourceCreator: 覆盖了AbstractBeanFactoryBasedTargetSourceCreator的方法. 设置为false, 即, 其
			//     迟延创建的目标源的scope全是默认值''
			bdCopy.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		}
		// TODO 把当前操作的bean所创建GenericBeanDefinition注册到内部容器中
		internalBeanFactory.registerBeanDefinition(beanName, bdCopy);

		// Complete configuring the PrototypeTargetSource.
		// TODO 然后设置目标源所持有的目标, 以及所使用的容器
		targetSource.setTargetBeanName(beanName);
		targetSource.setBeanFactory(internalBeanFactory);

		return targetSource;
	}

	/**
	 * Return the internal BeanFactory to be used for the specified bean.
	 * @param beanName the name of the target bean
	 * @return the internal BeanFactory to be used
	 */
	protected DefaultListableBeanFactory getInternalBeanFactoryForBean(String beanName) {
		synchronized (this.internalBeanFactories) {
			// TODO 如果缓存里没有, 会创建一个内部容器, 然后放到缓存中. 这个内部容器与当前容器相同, 只是去掉了用于处理Spring AOP
			//  的基础设施后处理器
			return this.internalBeanFactories.computeIfAbsent(beanName,
					name -> buildInternalBeanFactory(this.beanFactory));
		}
	}

	/**
	 * Build an internal BeanFactory for resolving target beans.
	 * @param containingFactory the containing BeanFactory that originally defines the beans
	 * @return an independent internal BeanFactory to hold copies of some target beans
	 */
	protected DefaultListableBeanFactory buildInternalBeanFactory(ConfigurableBeanFactory containingFactory) {
		// Set parent so that references (up container hierarchies) are correctly resolved.
		DefaultListableBeanFactory internalBeanFactory = new DefaultListableBeanFactory(containingFactory);

		// Required so that all BeanPostProcessors, Scopes, etc become available.
		internalBeanFactory.copyConfigurationFrom(containingFactory);

		// Filter out BeanPostProcessors that are part of the AOP infrastructure,
		// since those are only meant to apply to beans defined in the original factory.
		internalBeanFactory.getBeanPostProcessors().removeIf(beanPostProcessor ->
				beanPostProcessor instanceof AopInfrastructureBean);

		return internalBeanFactory;
	}

	/**
	 * Destroys the internal BeanFactory on shutdown of the TargetSourceCreator.
	 * @see #getInternalBeanFactoryForBean
	 */
	@Override
	public void destroy() {
		synchronized (this.internalBeanFactories) {
			for (DefaultListableBeanFactory bf : this.internalBeanFactories.values()) {
				bf.destroySingletons();
			}
		}
	}


	//---------------------------------------------------------------------
	// Template methods to be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Return whether this TargetSourceCreator is prototype-based.
	 * The scope of the target bean definition will be set accordingly.
	 * <p>Default is "true".
	 * @see org.springframework.beans.factory.config.BeanDefinition#isSingleton()
	 */
	protected boolean isPrototypeBased() {
		return true;
	}

	/**
	 * Subclasses must implement this method to return a new AbstractPrototypeBasedTargetSource
	 * if they wish to create a custom TargetSource for this bean, or {@code null} if they are
	 * not interested it in, in which case no special target source will be created.
	 * Subclasses should not call {@code setTargetBeanName} or {@code setBeanFactory}
	 * on the AbstractPrototypeBasedTargetSource: This class' implementation of
	 * {@code getTargetSource()} will do that.
	 * @param beanClass the class of the bean to create a TargetSource for
	 * @param beanName the name of the bean
	 * @return the AbstractPrototypeBasedTargetSource, or {@code null} if we don't match this
	 */
	@Nullable
	protected abstract AbstractBeanFactoryBasedTargetSource createBeanFactoryBasedTargetSource(
			Class<?> beanClass, String beanName);

}
