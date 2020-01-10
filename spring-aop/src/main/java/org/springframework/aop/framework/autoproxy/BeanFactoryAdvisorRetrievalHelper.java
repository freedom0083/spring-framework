/*
 * Copyright 2002-2018 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving standard Spring Advisors from a BeanFactory,
 * for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AbstractAdvisorAutoProxyCreator
 */
public class BeanFactoryAdvisorRetrievalHelper {

	private static final Log logger = LogFactory.getLog(BeanFactoryAdvisorRetrievalHelper.class);

	private final ConfigurableListableBeanFactory beanFactory;

	@Nullable
	private volatile String[] cachedAdvisorBeanNames;


	/**
	 * Create a new BeanFactoryAdvisorRetrievalHelper for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAdvisorRetrievalHelper(ConfigurableListableBeanFactory beanFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		this.beanFactory = beanFactory;
	}


	/**
	 * Find all eligible Advisor beans in the current bean factory,
	 * ignoring FactoryBeans and excluding beans that are currently in creation.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	// TODO 从容器中拿出所有符合要求的Advisor. 同时会对没实例化过的Advisor进行实例化操作
	public List<Advisor> findAdvisorBeans() {
		// Determine list of advisor bean names, if not cached already.
		// TODO 先从缓存中拿出所有的Advisor
		String[] advisorNames = this.cachedAdvisorBeanNames;
		if (advisorNames == null) {
			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the auto-proxy creator apply to them!
			// TODO 缓存没有时, 就需要重新加载当前容器中所有的Advisor, 这里会得到非单例Advisor, 同时不支持急加载
			advisorNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
					this.beanFactory, Advisor.class, true, false);
			// TODO 将结果设置到保存Advisor名字的缓存中
			this.cachedAdvisorBeanNames = advisorNames;
		}
		if (advisorNames.length == 0) {
			// TODO 没有找到任何Advisor时, 返回一个空的List
			return new ArrayList<>();
		}

		List<Advisor> advisors = new ArrayList<>();
		for (String name : advisorNames) {
			// TODO 遍历所有的Advisor, 只处理合格的Advisor. isEligibleBean()方法有以下实现:
			//  1. BeanFactoryAdvisorRetrievalHelper: 默认所有的Advisor全是合格的;
			//  2. AbstractAdvisorAutoProxyCreator$BeanFactoryAdvisorRetrievalHelperAdapter: BeanFactoryAdvisorRetrievalHelper
			//     的子类, 将具体操作代理给AbstractAdvisorAutoProxyCreator#isEligibleAdvisorBean():
			//     A. AbstractAdvisorAutoProxyCreator: 抽象类, 同样默认所有的Advisor全是合格的;
			//     B. InfrastructureAdvisorAutoProxyCreator: AbstractAdvisorAutoProxyCreator的子类, 判断Advisor在当前
			//        容器中的role是否为2(ROLE_INFRASTRUCTURE)
			//     C. DefaultAdvisorAutoProxyCreator: 通过前缀来识别Advisor是否合格. 没有前缀的, 或者前缀与专门为Advisor增强器
			//        设置的前缀相同时, 表示合格
			if (isEligibleBean(name)) {
				if (this.beanFactory.isCurrentlyInCreation(name)) {
					if (logger.isTraceEnabled()) {
						// TODO 当前Advisor正在创建时, 在允许追踪的前提下(debug), 会记个日志
						logger.trace("Skipping currently created advisor '" + name + "'");
					}
				}
				else {
					try {
						// TODO 还没创建的话, 就从容器里对其进行实例化, 然后放到返回的Advisor结果集中
						advisors.add(this.beanFactory.getBean(name, Advisor.class));
					}
					catch (BeanCreationException ex) {
						Throwable rootCause = ex.getMostSpecificCause();
						if (rootCause instanceof BeanCurrentlyInCreationException) {
							BeanCreationException bce = (BeanCreationException) rootCause;
							String bceBeanName = bce.getBeanName();
							if (bceBeanName != null && this.beanFactory.isCurrentlyInCreation(bceBeanName)) {
								if (logger.isTraceEnabled()) {
									logger.trace("Skipping advisor '" + name +
											"' with dependency on currently created bean: " + ex.getMessage());
								}
								// Ignore: indicates a reference back to the bean we're trying to advise.
								// We want to find advisors other than the currently created bean itself.
								// TODO 如果创建Advisor实例时出现异常, 对于BeanCurrentlyInCreationException这种循环依赖
								//  异常来说, 会打个log, 告诉你出现了循环依赖问题, 跳过当前正在创建的Advisor
								continue;
							}
						}
						// TODO 其他情况, 直接抛出异常
						throw ex;
					}
				}
			}
		}
		return advisors;
	}

	/**
	 * Determine whether the aspect bean with the given name is eligible.
	 * <p>The default implementation always returns {@code true}.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
