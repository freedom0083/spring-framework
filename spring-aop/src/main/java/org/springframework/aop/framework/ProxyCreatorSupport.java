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

package org.springframework.aop.framework;

import java.util.ArrayList;
import java.util.List;

import org.springframework.util.Assert;

/**
 * Base class for proxy factories.
 * Provides convenient access to a configurable AopProxyFactory.
 *
 * @author Juergen Hoeller
 * @since 2.0.3
 * @see #createAopProxy()
 */
@SuppressWarnings("serial")
// TODO 用于代理工厂的支持. 提供了创建AOP代理的功能, 但具体创建的实现是在DefaultAopProxyFactory中完成的. 如果是接口, 创建的是基于JDK
//  的动态代理. 如果是类, 创建的是基于CGLIB的代理
public class ProxyCreatorSupport extends AdvisedSupport {
	// TODO AOP代理工厂
	private AopProxyFactory aopProxyFactory;

	private final List<AdvisedSupportListener> listeners = new ArrayList<>();

	/** Set to true when the first AOP proxy has been created. */
	private boolean active = false;

	/**
	 * Create a new ProxyCreatorSupport instance.
	 */
	public ProxyCreatorSupport() {
		this.aopProxyFactory = new DefaultAopProxyFactory();
	}

	/**
	 * Create a new ProxyCreatorSupport instance.
	 * @param aopProxyFactory the AopProxyFactory to use
	 */
	public ProxyCreatorSupport(AopProxyFactory aopProxyFactory) {
		Assert.notNull(aopProxyFactory, "AopProxyFactory must not be null");
		this.aopProxyFactory = aopProxyFactory;
	}


	/**
	 * Customize the AopProxyFactory, allowing different strategies
	 * to be dropped in without changing the core framework.
	 * <p>Default is {@link DefaultAopProxyFactory}, using dynamic JDK
	 * proxies or CGLIB proxies based on the requirements.
	 */
	public void setAopProxyFactory(AopProxyFactory aopProxyFactory) {
		Assert.notNull(aopProxyFactory, "AopProxyFactory must not be null");
		this.aopProxyFactory = aopProxyFactory;
	}

	/**
	 * Return the AopProxyFactory that this ProxyConfig uses.
	 */
	public AopProxyFactory getAopProxyFactory() {
		return this.aopProxyFactory;
	}

	/**
	 * Add the given AdvisedSupportListener to this proxy configuration.
	 * @param listener the listener to register
	 */
	public void addListener(AdvisedSupportListener listener) {
		Assert.notNull(listener, "AdvisedSupportListener must not be null");
		this.listeners.add(listener);
	}

	/**
	 * Remove the given AdvisedSupportListener from this proxy configuration.
	 * @param listener the listener to deregister
	 */
	public void removeListener(AdvisedSupportListener listener) {
		Assert.notNull(listener, "AdvisedSupportListener must not be null");
		this.listeners.remove(listener);
	}


	/**
	 * Subclasses should call this to get a new AOP proxy. They should <b>not</b>
	 * create an AOP proxy with {@code this} as an argument.
	 */
	// TODO 创建一个AOP代理. 为子类服务的
	protected final synchronized AopProxy createAopProxy() {
		if (!this.active) {
			// TODO 首次创建AOP时需要先进激活存在的AdvisedSupportListener
			activate();
		}
		// TODO 用AOP代理工厂(默认是DefaultAopProxyFactory)创建一个AOP代理.
		//  1. 如果代理目标是接口, 使用JDK的动态代理;
		//  2. 如果代理目标是类, 使用CGLIB进行代理;
		return getAopProxyFactory().createAopProxy(this);
	}

	/**
	 * Activate this proxy configuration.
	 * @see AdvisedSupportListener#activated
	 */
	private void activate() {
		this.active = true;
		for (AdvisedSupportListener listener : this.listeners) {
			listener.activated(this);
		}
	}

	/**
	 * Propagate advice change event to all AdvisedSupportListeners.
	 * @see AdvisedSupportListener#adviceChanged
	 */
	@Override
	protected void adviceChanged() {
		super.adviceChanged();
		synchronized (this) {
			if (this.active) {
				for (AdvisedSupportListener listener : this.listeners) {
					listener.adviceChanged(this);
				}
			}
		}
	}

	/**
	 * Subclasses can call this to check whether any AOP proxies have been created yet.
	 */
	protected final synchronized boolean isActive() {
		return this.active;
	}

}
