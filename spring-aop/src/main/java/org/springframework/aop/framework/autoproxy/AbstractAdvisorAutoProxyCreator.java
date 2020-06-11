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

package org.springframework.aop.framework.autoproxy;

import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Generic auto proxy creator that builds AOP proxies for specific beans
 * based on detected Advisors for each bean.
 *
 * <p>Subclasses may override the {@link #findCandidateAdvisors()} method to
 * return a custom list of Advisors applying to any object. Subclasses can
 * also override the inherited {@link #shouldSkip} method to exclude certain
 * objects from auto-proxying.
 *
 * <p>Advisors or advices requiring ordering should be annotated with
 * {@link org.springframework.core.annotation.Order @Order} or implement the
 * {@link org.springframework.core.Ordered} interface. This class sorts
 * advisors using the {@link AnnotationAwareOrderComparator}. Advisors that are
 * not annotated with {@code @Order} or don't implement the {@code Ordered}
 * interface will be considered as unordered; they will appear at the end of the
 * advisor chain in an undefined order.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #findCandidateAdvisors
 */
@SuppressWarnings("serial")
public abstract class AbstractAdvisorAutoProxyCreator extends AbstractAutoProxyCreator {

	@Nullable
	private BeanFactoryAdvisorRetrievalHelper advisorRetrievalHelper;


	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		super.setBeanFactory(beanFactory);
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AdvisorAutoProxyCreator requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		initBeanFactory((ConfigurableListableBeanFactory) beanFactory);
	}

	protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// TODO AbstractAdvisorAutoProxyCreator初始化时创建的, 用来取得Advisor的默认Adapter
		this.advisorRetrievalHelper = new BeanFactoryAdvisorRetrievalHelperAdapter(beanFactory);
	}

	// TODO 取得容器中所有的Advisor. 对于普通的Advisor, 会对没有实例化的Advisor进行实例化操作. 如果支持AspectJ, 则还会为@Aspect切面
	//  中的@Around, @Before, @After, @AfterReturning, @AfterThrowing这些Advice方法, 以及@DeclareParents字段创建Advisor
	@Override
	@Nullable
	protected Object[] getAdvicesAndAdvisorsForBean(
			Class<?> beanClass, String beanName, @Nullable TargetSource targetSource) {
		// TODO 找到容器中所有适用的Advisor. 这里会对没有实例化的Advisor进行实例化. 如果支持AspectJ的话, 还会对AspectJ的注解进行
		//  处理, 为@Aspect切面中的@Around, @Before, @After, @AfterReturning, @AfterThrowing方法, 以及@DeclareParents字段创建Advisor
		List<Advisor> advisors = findEligibleAdvisors(beanClass, beanName);
		if (advisors.isEmpty()) {
			return DO_NOT_PROXY;
		}
		return advisors.toArray();
	}

	/**
	 * Find all eligible Advisors for auto-proxying this class.
	 * @param beanClass the clazz to find advisors for
	 * @param beanName the name of the currently proxied bean
	 * @return the empty List, not {@code null},
	 * if there are no pointcuts or interceptors
	 * @see #findCandidateAdvisors
	 * @see #sortAdvisors
	 * @see #extendAdvisors
	 */
	protected List<Advisor> findEligibleAdvisors(Class<?> beanClass, String beanName) {
		// TODO 找出容器中所有的候选Advisor
		//  1. AbstractAdvisorAutoProxyCreator: 抽象类. 取得Advisor的过程是委托BeanFactoryAdvisorRetrievalHelper#findAdvisorBeans()
		//     进行的, 会对没实例化过的Advisor进行实例化操作
		//  2. AnnotationAwareAspectJAutoProxyCreator: AbstractAdvisorAutoProxyCreator的子类, 用于处理容器中的AspectJ注解.
		//     除了抽象类中的Advisor外, 还会为被@Aspect所标注的切面中的所有被@Around, @Before, @After, @AfterReturning,
		//     @AfterThrowing标注的方法, 以及被@DeclareParents标注的字段创建Advisor
		List<Advisor> candidateAdvisors = findCandidateAdvisors();
		// TODO 找出可以用于当前操作的bean的Advisor
		List<Advisor> eligibleAdvisors = findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName);
		// TODO 勾子方法, 用于子类进行覆盖, 本身没提供实现. AspectJAwareAdvisorAutoProxyCreator
		extendAdvisors(eligibleAdvisors);
		if (!eligibleAdvisors.isEmpty()) {
			eligibleAdvisors = sortAdvisors(eligibleAdvisors);
		}
		return eligibleAdvisors;
	}

	/**
	 * Find all candidate Advisors to use in auto-proxying.
	 * @return the List of candidate Advisors
	 */
	// TODO 取得所有用于自动代理的候选Advisor, 会对没实例化过的Advisor进行实例化操作
	protected List<Advisor> findCandidateAdvisors() {
		Assert.state(this.advisorRetrievalHelper != null, "No BeanFactoryAdvisorRetrievalHelper available");
		// TODO 委托BeanFactoryAdvisorRetrievalHelper#findAdvisorBeans()来取得容器中所有可以使用的Advisor. 会对没实例化过的Advisor进行实例化操作
		return this.advisorRetrievalHelper.findAdvisorBeans();
	}

	/**
	 * Search the given candidate Advisors to find all Advisors that
	 * can apply to the specified bean.
	 * @param candidateAdvisors the candidate Advisors 可用的候选Advisor
	 * @param beanClass the target's bean class 代理目标类
	 * @param beanName the target's bean name 代理目标类的名字
	 * @return the List of applicable Advisors
	 * @see ProxyCreationContext#getCurrentProxiedBeanName()
	 */
	// TODO 找出所有可以用于当前操作的bean的Advisor
	protected List<Advisor> findAdvisorsThatCanApply(
			List<Advisor> candidateAdvisors, Class<?> beanClass, String beanName) {
		// TODO 这个ProxyCreationContext上下文中, 用ThreadLocal来保存的要实例化的bean的名字
		ProxyCreationContext.setCurrentProxiedBeanName(beanName);
		try {
			// TODO 找出可以用于代理目标的Advisor
			return AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass);
		}
		finally {
			// TODO 整个创建过程结果后, 清掉ThreadLocal
			ProxyCreationContext.setCurrentProxiedBeanName(null);
		}
	}

	/**
	 * Return whether the Advisor bean with the given name is eligible
	 * for proxying in the first place.
	 * @param beanName the name of the Advisor bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleAdvisorBean(String beanName) {
		return true;
	}

	/**
	 * Sort advisors based on ordering. Subclasses may choose to override this
	 * method to customize the sorting strategy.
	 * @param advisors the source List of Advisors
	 * @return the sorted List of Advisors
	 * @see org.springframework.core.Ordered
	 * @see org.springframework.core.annotation.Order
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 */
	protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
		AnnotationAwareOrderComparator.sort(advisors);
		return advisors;
	}

	/**
	 * Extension hook that subclasses can override to register additional Advisors,
	 * given the sorted Advisors obtained to date.
	 * <p>The default implementation is empty.
	 * <p>Typically used to add Advisors that expose contextual information
	 * required by some of the later advisors.
	 * @param candidateAdvisors the Advisors that have already been identified as
	 * applying to a given bean
	 */
	protected void extendAdvisors(List<Advisor> candidateAdvisors) {
	}

	/**
	 * This auto-proxy creator always returns pre-filtered Advisors.
	 */
	@Override
	protected boolean advisorsPreFiltered() {
		return true;
	}


	/**
	 * Subclass of BeanFactoryAdvisorRetrievalHelper that delegates to
	 * surrounding AbstractAdvisorAutoProxyCreator facilities.
	 */
	private class BeanFactoryAdvisorRetrievalHelperAdapter extends BeanFactoryAdvisorRetrievalHelper {

		public BeanFactoryAdvisorRetrievalHelperAdapter(ConfigurableListableBeanFactory beanFactory) {
			super(beanFactory);
		}

		@Override
		protected boolean isEligibleBean(String beanName) {
			// TODO 委托给自己的isEligibleAdvisorBean()方法:
			//   1. AbstractAdvisorAutoProxyCreator: 抽象类, 同样默认所有的Advisor全是合格的;
			//   2. InfrastructureAdvisorAutoProxyCreator: AbstractAdvisorAutoProxyCreator的子类, 判断Advisor在当前容器
			//      中的role是否为2(ROLE_INFRASTRUCTURE)
			//   3. DefaultAdvisorAutoProxyCreator: 通过前缀来识别Advisor是否合格. 没有前缀的, 或者前缀与专门为Advisor
			//      设置的前缀相同时, 表示合格
			return AbstractAdvisorAutoProxyCreator.this.isEligibleAdvisorBean(beanName);
		}
	}

}
