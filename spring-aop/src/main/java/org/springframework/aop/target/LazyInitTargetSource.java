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

package org.springframework.aop.target;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeansException;

/**
 * {@link org.springframework.aop.TargetSource} that lazily accesses a
 * singleton bean from a {@link org.springframework.beans.factory.BeanFactory}.
 *
 * <p>Useful when a proxy reference is needed on initialization but
 * the actual target object should not be initialized until first use.
 * When the target bean is defined in an
 * {@link org.springframework.context.ApplicationContext} (or a
 * {@code BeanFactory} that is eagerly pre-instantiating singleton beans)
 * it must be marked as "lazy-init" too, else it will be instantiated by said
 * {@code ApplicationContext} (or {@code BeanFactory}) on startup.
 * <p>For example:
 *
 * <pre class="code">
 * &lt;bean id="serviceTarget" class="example.MyService" lazy-init="true"&gt;
 *   ...
 * &lt;/bean&gt;
 *
 * &lt;bean id="service" class="org.springframework.aop.framework.ProxyFactoryBean"&gt;
 *   &lt;property name="targetSource"&gt;
 *     &lt;bean class="org.springframework.aop.target.LazyInitTargetSource"&gt;
 *       &lt;property name="targetBeanName"&gt;&lt;idref local="serviceTarget"/&gt;&lt;/property&gt;
 *     &lt;/bean&gt;
 *   &lt;/property&gt;
 * &lt;/bean&gt;</pre>
 *
 * The "serviceTarget" bean will not get initialized until a method on the
 * "service" proxy gets invoked.
 *
 * <p>Subclasses can extend this class and override the {@link #postProcessTargetObject(Object)} to
 * perform some additional processing with the target object when it is first loaded.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 1.1.4
 * @see org.springframework.beans.factory.BeanFactory#getBean
 * @see #postProcessTargetObject
 */
@SuppressWarnings("serial")
// TODO 用于延迟初始化目标源, 只要在调用了getTarget()方法后才会进行初始化动作
public class LazyInitTargetSource extends AbstractBeanFactoryBasedTargetSource {

	private @Nullable Object target;


	@Override
	public synchronized Object getTarget() throws BeansException {
		if (this.target == null) {
			// TODO 作用就是当调用getTarget()方法时才会进行初始化动作
			this.target = getBeanFactory().getBean(getTargetBeanName());
			// TODO 然后可以对代理目标进行一此其他处理. 当前类并没有做实现, 子类可以对其进行覆盖.
			//  目前MbeanExporter$NotificationPublisherAwareLazyTargetSource对其进行了覆盖
			postProcessTargetObject(this.target);
		}
		return this.target;
	}

	/**
	 * Subclasses may override this method to perform additional processing on
	 * the target object when it is first loaded.
	 * @param targetObject the target object that has just been instantiated (and configured)
	 */
	protected void postProcessTargetObject(Object targetObject) {
	}

}
