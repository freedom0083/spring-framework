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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.ResolvableType;

/**
 * Support base class for singleton registries which need to handle
 * {@link org.springframework.beans.factory.FactoryBean} instances,
 * integrated with {@link DefaultSingletonBeanRegistry}'s singleton management.
 *
 * <p>Serves as base class for {@link AbstractBeanFactory}.
 *
 * @author Juergen Hoeller
 * @since 2.5.1
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/** Cache of singleton objects created by FactoryBeans: FactoryBean name to object. */
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);


	/**
	 * Determine the type for the given FactoryBean.
	 * @param factoryBean the FactoryBean instance to check
	 * @return the FactoryBean's object type,
	 * or {@code null} if the type cannot be determined yet
	 */
	protected @Nullable Class<?> getTypeForFactoryBean(FactoryBean<?> factoryBean) {
		try {
			return factoryBean.getObjectType();
		}
		catch (Throwable ex) {
			// Thrown from the FactoryBean's getObjectType implementation.
			logger.info("FactoryBean threw exception from getObjectType, despite the contract saying " +
					"that it should return null if the type of its object cannot be determined yet", ex);
			return null;
		}
	}

	/**
	 * Determine the bean type for a FactoryBean by inspecting its attributes for a
	 * {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} value.
	 * @param attributes the attributes to inspect
	 * @return a {@link ResolvableType} extracted from the attributes or
	 * {@code ResolvableType.NONE}
	 * @since 5.2
	 */
	ResolvableType getTypeForFactoryBeanFromAttributes(AttributeAccessor attributes) {
		Object attribute = attributes.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
		if (attribute == null) {
			return ResolvableType.NONE;
		}
		if (attribute instanceof ResolvableType resolvableType) {
			return resolvableType;
		}
		if (attribute instanceof Class<?> clazz) {
			return ResolvableType.forClass(clazz);
		}
		throw new IllegalArgumentException("Invalid value type for attribute '" +
				FactoryBean.OBJECT_TYPE_ATTRIBUTE + "': " + attribute.getClass().getName());
	}

	/**
	 * Determine the FactoryBean object type from the given generic declaration.
	 * @param type the FactoryBean type
	 * @return the nested object type, or {@code NONE} if not resolvable
	 */
	ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		return (type != null ? type.as(FactoryBean.class).getGeneric() : ResolvableType.NONE);
	}

	/**
	 * Obtain an object to expose from the given FactoryBean, if available
	 * in cached form. Quick check for minimal synchronization.
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean,
	 * or {@code null} if not available
	 */
	protected @Nullable Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 * @param factory the FactoryBean instance
	 * @param beanName the name of the bean
	 * @param shouldPostProcess whether the bean is subject to post-processing
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, @Nullable Class<?> requiredType,
			String beanName, boolean shouldPostProcess) {

		if (factory.isSingleton() && containsSingleton(beanName)) {
			// TODO 工厂类是单例, 并且要找的bean已经存在于singletonObjects缓存中时, 获取bean的动作需要对singletonObjects进行加锁来保证线程安全问题
			Boolean lockFlag = isCurrentThreadAllowedToHoldSingletonLock();
			boolean locked;
			if (lockFlag == null) {
				this.singletonLock.lock();
				locked = true;
			}
			else {
				locked = (lockFlag && this.singletonLock.tryLock());
			}
			try {
				// A SmartFactoryBean may return multiple object types -> do not cache.
				// TODO 首先从factoryBeanObjectCache缓存取得对应的bean, 如果得到直接返回
				boolean smart = (factory instanceof SmartFactoryBean<?>);
				Object object = (!smart ? this.factoryBeanObjectCache.get(beanName) : null);
				if (object == null) {
					// TODO 如果没有取到, 就通过getObject()从工厂类中取, 这里就是前面说的不加'&'时, 实际是取出的FactoryBean内的对象
					object = doGetObjectFromFactoryBean(factory, requiredType, beanName);
					// Only post-process and store if not put there already during getObject() call above
					// (for example, because of circular reference processing triggered by custom getBean calls)
					Object alreadyThere = (!smart ? this.factoryBeanObjectCache.get(beanName) : null);
					if (alreadyThere != null) {
						// TODO double check如果在factoryBeanObjectCache缓存中, 则用缓存中的实例
						object = alreadyThere;
					}
					else {
						// TODO 缓存中没有时, 就准备开始对bean进行后处理了
						if (shouldPostProcess) {
							if (locked) {
								// TODO 在允许对bean进行后处理时, 判断一下指定的bean是否正在进行创建
								if (isSingletonCurrentlyInCreation(beanName)) {
									// Temporarily return non-post-processed object, not storing it yet
									// TODO 如果正在创建, 则直接返回
									return object;
								}
								// TODO 回调函数, 目前缺省实现是: 将bean注册到正在创建缓存singletonsCurrentlyInCreation中
								//  如果注册失败, 并且还不在排除列表里时, 抛出BeanCurrentlyInCreationException异常
								beforeSingletonCreation(beanName);
							}
							try {
								// TODO 对取得的原始bean对象进行后处理
								//  1. 默认实现: 不做任何处理, 直接返回原始bean对象
								//  2. AbstractAutowireCapableBeanFactory实现: 挨个调用BeanPostProcessor.postProcessAfterInitialization()
								//     在原始bean初始化后进行处理并返回加工后的bean对象(实现自动注入等功能)
								object = postProcessObjectFromFactoryBean(object, beanName);
							}
							catch (Throwable ex) {
								throw new BeanCreationException(beanName,
										"Post-processing of FactoryBean's singleton object failed", ex);
							}
							finally {
								if (locked) {
									// TODO 回调函数, 目前缺省实现是: 将bean从正在创建缓存singletonsCurrentlyInCreation中注销
									//  如果注销失败, 并且还不在排除列表里时, 抛出IllegalStateException异常
									afterSingletonCreation(beanName);
								}
							}
						}
						if (!smart && containsSingleton(beanName)) {
							// TODO singletonObjects缓存中存在待查找bean时, 将其放入factoryBeanObjectCache缓存
							this.factoryBeanObjectCache.put(beanName, object);
						}
					}
				}
				// TODO 返回一个可能经过后处理器加工后的bean对象
				return object;
			}
			finally {
				if (locked) {
					this.singletonLock.unlock();
				}
			}
		}
		else {
			// TODO 工厂类不是单例, 或要找的bean不在singletonObjects缓存中时, 就直接从工厂类中拿原始bean对象
			Object object = doGetObjectFromFactoryBean(factory, requiredType, beanName);
			if (shouldPostProcess) {
				try {
					// TODO 然后和单例处理方式一样, 挨个儿调用后处理器对原始bean对象进行加工并返回
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(beanName, "Post-processing of FactoryBean's object failed", ex);
				}
			}
			return object;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 * @param factory the FactoryBean instance
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, @Nullable Class<?> requiredType, String beanName)
			throws BeanCreationException {

		Object object;
		try {
			// 调用工厂类的getObject()取得原始bean. 如果存在于缓存singletonInstance
			//  或earlySingletonInstance中, 直接从缓存获取, 否则创建一个实例
			object = (requiredType != null && factory instanceof SmartFactoryBean<?> smartFactoryBean ?
					smartFactoryBean.getObject(requiredType) : factory.getObject());
		}
		catch (FactoryBeanNotInitializedException ex) {
			throw new BeanCurrentlyInCreationException(beanName, ex.toString());
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "FactoryBean threw exception on object creation", ex);
		}

		// Do not accept a null value for a FactoryBean that's not fully
		// initialized yet: Many FactoryBeans just return null then.
		if (object == null) {
			if (isSingletonCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(
						beanName, "FactoryBean which is currently in creation returned null from getObject");
			}
			object = new NullBean();
		}
		return object;
	}

	/**
	 * Post-process the given object that has been obtained from the FactoryBean.
	 * The resulting object will get exposed for bean references.
	 * <p>The default implementation simply returns the given object as-is.
	 * Subclasses may override this, for example, to apply post-processors.
	 * @param object the object obtained from the FactoryBean.
	 * @param beanName the name of the bean
	 * @return the object to expose
	 * @throws org.springframework.beans.BeansException if any post-processing failed
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) throws BeansException {
		return object;
	}

	/**
	 * Get a FactoryBean for the given bean if possible.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance
	 * @return the bean instance as FactoryBean
	 * @throws BeansException if the given bean cannot be exposed as a FactoryBean
	 */
	protected FactoryBean<?> getFactoryBean(String beanName, Object beanInstance) throws BeansException {
		if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
			throw new BeanCreationException(beanName,
					"Bean instance of type [" + beanInstance.getClass() + "] is not a FactoryBean");
		}
		return factoryBean;
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		super.removeSingleton(beanName);
		this.factoryBeanObjectCache.remove(beanName);
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		super.clearSingletonCache();
		this.factoryBeanObjectCache.clear();
	}

}
