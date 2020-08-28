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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving @AspectJ beans from a BeanFactory and building
 * Spring Advisors based on them, for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AnnotationAwareAspectJAutoProxyCreator
 */
public class BeanFactoryAspectJAdvisorsBuilder {

	private final ListableBeanFactory beanFactory;

	private final AspectJAdvisorFactory advisorFactory;

	@Nullable
	private volatile List<String> aspectBeanNames;

	private final Map<String, List<Advisor>> advisorsCache = new ConcurrentHashMap<>();

	private final Map<String, MetadataAwareAspectInstanceFactory> aspectFactoryCache = new ConcurrentHashMap<>();


	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory) {
		this(beanFactory, new ReflectiveAspectJAdvisorFactory(beanFactory));
	}

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 * @param advisorFactory the AspectJAdvisorFactory to build each Advisor with
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		Assert.notNull(advisorFactory, "AspectJAdvisorFactory must not be null");
		this.beanFactory = beanFactory;
		this.advisorFactory = advisorFactory;
	}


	/**
	 * Look for AspectJ-annotated aspect beans in the current bean factory,
	 * and return to a list of Spring AOP Advisors representing them.
	 * <p>Creates a Spring Advisor for each AspectJ advice method.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> buildAspectJAdvisors() {
		// TODO 先从容器中拿所有被@Aspect注解的Bean
		List<String> aspectNames = this.aspectBeanNames;
		// TODO 如果没有, 需要在同步的情况下重新取得一下
		if (aspectNames == null) {
			synchronized (this) {
				aspectNames = this.aspectBeanNames;
				if (aspectNames == null) {
					List<Advisor> advisors = new ArrayList<>();
					aspectNames = new ArrayList<>();
					// TODO 取容器中所有的Object类型的Bean, 包括非单例, 但不支持急加载
					String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
							this.beanFactory, Object.class, true, false);
					for (String beanName : beanNames) {
						// TODO 遍历容器中所有的Object
						if (!isEligibleBean(beanName)) {
							// TODO 跳过所有不符合AspectJ命名规范的bean, 这里会和预设的Pattern进行正则匹配
							continue;
						}
						// We must be careful not to instantiate beans eagerly as in this case they
						// would be cached by the Spring container but would not have been weaved.
						// TODO 取得当前Bean的类型, 用来进行后面的AspectJ的类型判断
						Class<?> beanType = this.beanFactory.getType(beanName, false);
						if (beanType == null) {
							continue;
						}
						if (this.advisorFactory.isAspect(beanType)) {
							// TODO 只处理被@Aspect注解过, 但没有被AspectJ编译过的bean. 这样的bean名会入到集合中, 稍后会更新到aspectBeanNames缓存中
							aspectNames.add(beanName);
							// TODO 为bean创建AspectJ的元数据实例, 这里会设置切面实例类型. 目前Spring AOP只支持AspectJ的
							//  singleton, perthis, pertarget, pertypewithin四种实例类型, 不支持percflow和percflowbelow类型
							AspectMetadata amd = new AspectMetadata(beanType, beanName);
							if (amd.getAjType().getPerClause().getKind() == PerClauseKind.SINGLETON) {
								// TODO 对于SINGLETON实例类型来说, 会创建用于获取Advisor的BeanFactoryAspectInstanceFactory,
								//  这个工厂在创建时会对切面进行解析, 将结果放到Aspect元数据里. 后面的操作都是基于工厂中缓存的元数据来进行的
								MetadataAwareAspectInstanceFactory factory =
										new BeanFactoryAspectInstanceFactory(this.beanFactory, beanName);
								// TODO 开始为切面创建Advisor. 实际上Spring AOP使用ReflectiveAspectJAdvisorFactory来为
								//  切面中的所有被@Around, @Before, @After, @AfterReturning, @AfterThrowing标注的方法, 以及
								//  被@DeclareParents标注的字段创建Advisor. 注意的是这里不处理表示切点的@Pointcut注解
								//  TIPS 这里传进去的工厂会被包装成一个具有懒加载功能的单例工厂, 为的是防止多次实例化
								List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(factory);
								if (this.beanFactory.isSingleton(beanName)) {
									// TODO 单例bean放到advisorsCache缓存
									this.advisorsCache.put(beanName, classAdvisors);
								}
								else {
									// TODO 非单例bean则把上面的工厂与bean组合放到aspectFactoryCache缓存中. 非单例bean每次都会
									//  用工厂取得一个全新的实例
									this.aspectFactoryCache.put(beanName, factory);
								}
								// TODO 然后把这些Advisor全部放到候选结果集中
								advisors.addAll(classAdvisors);
							}
							else {
								// Per target or per this.
								// TODO 其他切面实例类型来说
								if (this.beanFactory.isSingleton(beanName)) {
									// TODO 不允许其为单例. 即, 切面实例必需与其在容器中的Scope相同, 要么全是单例, 要么全不是单例
									throw new IllegalArgumentException("Bean with name '" + beanName +
											"' is a singleton, but aspect instantiation model is not singleton");
								}
								// TODO 其他类型的切面则创建PrototypeAspectInstanceFactory来获取Advisor
								MetadataAwareAspectInstanceFactory factory =
										new PrototypeAspectInstanceFactory(this.beanFactory, beanName);
								// TODO 对于非单例bean, 直接创建MetadataAwareAspectInstanceFactory放到缓存中, 每次取得切面时
								//  都会从工厂取得一个全新的实例
								this.aspectFactoryCache.put(beanName, factory);
								// TODO 然后再把工厂中的Advisor放到结果集中
								advisors.addAll(this.advisorFactory.getAdvisors(factory));
							}
						}
					}
					// TODO 更新一下缓存, 然后返回所有Advisor
					this.aspectBeanNames = aspectNames;
					return advisors;
				}
			}
		}
		// TODO 下面就是缓存中有的情况了
		if (aspectNames.isEmpty()) {
			// TODO 这个判断需要么??
			return Collections.emptyList();
		}
		List<Advisor> advisors = new ArrayList<>();
		for (String aspectName : aspectNames) {
			// TODO 从单例缓存中拿出所有Advisor
			List<Advisor> cachedAdvisors = this.advisorsCache.get(aspectName);
			if (cachedAdvisors != null) {
				// TODO 拿到的就放到结果集中
				advisors.addAll(cachedAdvisors);
			}
			else {
				// TODO 没拿到就表示这个Advisor不是单例的, 非单例bean在Spring中每次都会创建一个新的实例. 这时就会根据切点的
				//  Scope选择不同的MetadataAwareAspectInstanceFactory工厂进行创建了(创建这一步来说, 这俩工厂没什么区别, 其实都是
				//  用工厂中缓存的切面信息进行创建的):
				//  1. BeanFactoryAspectInstanceFactory: 对应SINGLETON类型切面实例
				//  2. PrototypeAspectInstanceFactory: 对应其他类型切面实例
				MetadataAwareAspectInstanceFactory factory = this.aspectFactoryCache.get(aspectName);
				advisors.addAll(this.advisorFactory.getAdvisors(factory));
			}
		}
		return advisors;
	}

	/**
	 * Return whether the aspect bean with the given name is eligible.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
