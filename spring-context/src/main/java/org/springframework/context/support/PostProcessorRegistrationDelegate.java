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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionValueResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		Set<String> processedBeans = new HashSet<>();

		// TODO 判断beanFactory是否为BeanDefinitionRegistry类型
		if (beanFactory instanceof BeanDefinitionRegistry registry) {
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			// TODO 在容器进行初始化过程时, AbstractApplicationContext#refresh()方法中调用了obtainFreshBeanFactory()方法生成
			//  了一个DefaultListableBeanFactory类型的BeanFactory放到容器的beanFactory属性中. 由于DefaultListableBeanFactory
			//  实现了BeanDefinitionRegistry接口, 因此在初始化过程中会直接进入到这里.
			//  存放BeanDefinitionRegistryPostProcessor的缓存
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();
			// TODO beanFactoryPostProcessors中所有的对象都是在ApplicationContext#addBeanFactoryPostProcessor()手动注册进去的
			//  如果没有进行过手动注册, beanFactoryPostProcessors一般情况下都是空的, 这里区分了BeanDefinitionRegistryPostProcessor
			//  和普通BeanFactoryPostProcessor
			//    1. BeanDefinitionRegistryPostProcessor: 通过postProcessBeanDefinitionRegistry()方法实现自定义类加载
			//    2. BeanFactoryPostProcessor: 普通的容器级后处理器
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor registryProcessor) {
					// TODO 处理手动注册的BeanFactoryPostProcessor后处理器是BeanDefinitionRegistryPostProcessor类型的情况
					//  调用后处理器的postProcessBeanDefinitionRegistry()执行自定义bean注册动作
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					// TODO 放入BeanDefinitionRegistryPostProcessor缓存
					registryProcessors.add(registryProcessor);
				}
				else {
					// TODO 其他类型的后处理器都放入BeanFactoryPostProcessor缓存, 后面统一处理
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			// TODO 拿到所有通过配置文件注册到容器中, 并且实现了BeanDefinitionRegistryPostProcessor接口的, 支持优先级排序的后处理器.
			//  如果没有自定义的实现类时, 这里只包括AnnotationConfigApplicationContext初始化reader时,
			//  由AnnotationConfigUtils#registerAnnotationConfigProcessors()方法注册的后处理器ConfigurationClassPostProcessor,
			//  及其内部类ImportAwareBeanPostProcessor. 注意, 这里是包括非单例实例, 以及支持急加载的:
			//    1. ConfigurationClassPostProcessor: 解析@Configuraton配置类,并将解析结果(@Bean方法等)注册到容器
			//    2. ImportAwareBeanPostProcessor: ConfigurationClassPostProcessor的内部类, 处理ImportRegistry的情况
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				// TODO 首先看一下这些后处理器有没有支持优先级排序的
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// TODO 通过beanFactory#getBean()实例化支持优先级排序的BeanDefinitionRegistryPostProcessor后处理器
					//  并将其放到记录当前注册的后处理器缓存中. 这步执行完成后, 每个执行的后处理器就全部被实例化过了.
					//  后处理器不可能是一个工厂类??? Mark
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					// TODO 放入'已处理的bean'集合的缓存中, 防止重复处理
					processedBeans.add(ppName);
				}
			}
			// TODO 对优先级进行排序如果, 得到一个有顺序的currentRegistryProcessors集合
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// TODO 放入BeanDefinitionRegistryPostProcessor缓存
			registryProcessors.addAll(currentRegistryProcessors);
			// TODO 挨个儿调用这些后处理器的postProcessBeanDefinitionRegistry()方法, 实现每个后处理器的自定义bean注册功能
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			// TODO 执行后清空缓存, 后面会放入其他的后处理器
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					// TODO 再用同样的方式处理支持排序的BeanDefinitionRegistryPostProcessor后处理器
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// TODO 放入BeanDefinitionRegistryPostProcessor缓存
			registryProcessors.addAll(currentRegistryProcessors);
			// TODO 挨个儿调用这些后处理器的postProcessBeanDefinitionRegistry()方法, 实现每个后处理器的自定义bean注册功能
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				// TODO 找出所有实现BeanDefinitionRegistryPostProcessor接口的类
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					// TODO 跳过所有执行过的
					if (!processedBeans.contains(ppName)) {
						// TODO 用同样的方式处理支持普通的BeanDefinitionRegistryPostProcessor后处理器
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						// TODO 如果有BeanDefinitionRegistryPostProcessor被执行, 则有可能会产生新的BeanDefinitionRegistryPostProcessor,
						//  因此这边将reiterate赋值为true, 代表需要再循环查找一次
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				// TODO 放入BeanDefinitionRegistryPostProcessor缓存
				registryProcessors.addAll(currentRegistryProcessors);
				// TODO 挨个儿调用这些后处理器的postProcessBeanDefinitionRegistry()方法, 实现每个后处理器的自定义bean注册功能
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			// TODO 调用缓存中所有BeanDefinitionRegistryPostProcessor后处理器的postProcessBeanFactory()方法
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			// TODO 调用缓存中所有普通的后处理器的postProcessBeanFactory()方法
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			// TODO 调用其他BeanFactory(非BeanDefinitionRegistry)手动注册的后处理器
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		// TODO 上面处理了所有的BeanDefinitionRegistryPostProcessor后处理器, 现在开始以同样的方式处理BeanFactoryPostProcessor后处理器
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// TODO 实现了PriorityOrdered接口的BeanFactoryPostProcessor缓存
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		// TODO 实现了Ordered接口的BeanFactoryPostProcessor的beanName缓存
		List<String> orderedPostProcessorNames = new ArrayList<>();
		// TODO 普通BeanFactoryPostProcessor的beanName的缓存
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			// TODO 跳过已经处理过的后处理器
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				// TODO 通过getBean()方法实例化实现了PriorityOrdered接口的BeanFactoryPostProcessor, 然后放入缓存
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				// TODO 把支持排序的BeanFactoryPostProcessor名字放入缓存
				orderedPostProcessorNames.add(ppName);
			}
			else {
				// TODO 把普通的BeanFactoryPostProcessor名字放入缓存
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		// TODO 排序支持PriorityOrdered接口的BeanFactoryPostProcessor
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		// TODO 调用支持PriorityOrdered接口的BeanFactoryPostProcessor的后处理器的postProcessBeanFactory()方法
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		// TODO 实现了Ordered接口的BeanFactoryPostProcessor缓存
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			// TODO 通过getBean()方法实例化实现了Ordered接口的BeanFactoryPostProcessor, 然后放入缓存
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		// TODO 排序支持Ordered接口的BeanFactoryPostProcessor
		sortPostProcessors(orderedPostProcessors, beanFactory);
		// TODO 调用支持Ordered接口的BeanFactoryPostProcessor的后处理器的postProcessBeanFactory()方法
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			// TODO 通过getBean()方法实例化剩下的BeanFactoryPostProcessor, 然后放入缓存
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		// TODO 调用剩下的BeanFactoryPostProcessor的后处理器的postProcessBeanFactory()方法
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22
		// TODO 取得所有的BeanPostProcessor后处理器名
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		// TODO BeanPostProcessorChecker主要是为了在后处理器实例化期间(所有的后处理器还没全部处理完), 在处理某个bean时起到一个通知作用,
		//  表示还有后处理器没处理完，所以当前处理的这个bean, 可能还不能被所有后处理器进行处理
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			// TODO 把所有后处理器根据PriorityOrdered, Ordered, rest进行分类, 放到各自处理的队列中
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				// TODO 对于实现了优先级排序的后处理器, 这边还会通过getBean()直接进行实例化
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		// TODO 注册PriorityOrdered的后处理器, 先排个序, 然后注册一下
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		// TODO 接着处理Order的后处理器，初始化后，排个序再注册一下
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		// TODO 再处理非排序的普通后处理器，初始化后注册一下
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		// TODO 最后再把属性合并后处理器注册一下
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	/**
	 * Load and sort the post-processors of the specified type.
	 * @param beanFactory the bean factory to use
	 * @param beanPostProcessorType the post-processor type
	 * @param <T> the post-processor type
	 * @return a list of sorted post-processors for the specified type
	 */
	static <T extends BeanPostProcessor> List<T> loadBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, Class<T> beanPostProcessorType) {

		String[] postProcessorNames = beanFactory.getBeanNamesForType(beanPostProcessorType, true, false);
		List<T> postProcessors = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			postProcessors.add(beanFactory.getBean(ppName, beanPostProcessorType));
		}
		sortPostProcessors(postProcessors, beanFactory);
		return postProcessors;

	}

	/**
	 * Selectively invoke {@link MergedBeanDefinitionPostProcessor} instances
	 * registered in the specified bean factory, resolving bean definitions as
	 * well as any inner bean definitions that they may contain.
	 * @param beanFactory the bean factory to use
	 */
	static void invokeMergedBeanDefinitionPostProcessors(DefaultListableBeanFactory beanFactory) {
		new MergedBeanDefinitionPostProcessorInvoker(beanFactory).invokeMergedBeanDefinitionPostProcessors();
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
					.tag("postProcessor", postProcessor::toString);
			// TODO 挨个执行BeanDefinitionRegistryPostProcessor的postProcessBeanDefinitionRegistry()方法
			postProcessor.postProcessBeanDefinitionRegistry(registry);
			postProcessBeanDefRegistry.end();
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanFactory = beanFactory.getApplicationStartup().start("spring.context.bean-factory.post-process")
					.tag("postProcessor", postProcessor::toString);
			// TODO 挨个儿执行每个后处理器
			postProcessor.postProcessBeanFactory(beanFactory);
			postProcessBeanFactory.end();
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			((AbstractBeanFactory) beanFactory).addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			// TODO 在bean不是个后处理器，也不是Sprint自身要创建的，同时后处理器还没完全创建完时, 会报出一个表示当前bean不能被所有后处理器
			//  处理的一个info
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		// TODO 检查一下bean是否为Spring自身需要创建的Bean, 而非应用通过@Component, @Configuration等注解或XML配置文件创建的Bean
		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

	private static final class MergedBeanDefinitionPostProcessorInvoker {

		private final DefaultListableBeanFactory beanFactory;

		private MergedBeanDefinitionPostProcessorInvoker(DefaultListableBeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		private void invokeMergedBeanDefinitionPostProcessors() {
			List<MergedBeanDefinitionPostProcessor> postProcessors = PostProcessorRegistrationDelegate.loadBeanPostProcessors(
					this.beanFactory, MergedBeanDefinitionPostProcessor.class);
			for (String beanName : this.beanFactory.getBeanDefinitionNames()) {
				RootBeanDefinition bd = (RootBeanDefinition) this.beanFactory.getMergedBeanDefinition(beanName);
				Class<?> beanType = resolveBeanType(bd);
				postProcessRootBeanDefinition(postProcessors, beanName, beanType, bd);
			}
		}

		private void postProcessRootBeanDefinition(List<MergedBeanDefinitionPostProcessor> postProcessors,
				String beanName, Class<?> beanType, RootBeanDefinition bd) {
			BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this.beanFactory, beanName, bd);
			postProcessors.forEach(postProcessor -> postProcessor.postProcessMergedBeanDefinition(bd, beanType, beanName));
			for (PropertyValue propertyValue : bd.getPropertyValues().getPropertyValueList()) {
				Object value = propertyValue.getValue();
				if (value instanceof AbstractBeanDefinition innerBd) {
					Class<?> innerBeanType = resolveBeanType(innerBd);
					resolveInnerBeanDefinition(valueResolver, innerBd, (innerBeanName, innerBeanDefinition)
							-> postProcessRootBeanDefinition(postProcessors, innerBeanName, innerBeanType, innerBeanDefinition));
				}
			}
			for (ValueHolder valueHolder : bd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
				Object value = valueHolder.getValue();
				if (value instanceof AbstractBeanDefinition innerBd) {
					Class<?> innerBeanType = resolveBeanType(innerBd);
					resolveInnerBeanDefinition(valueResolver, innerBd, (innerBeanName, innerBeanDefinition)
							-> postProcessRootBeanDefinition(postProcessors, innerBeanName, innerBeanType, innerBeanDefinition));
				}
			}
		}

		private void resolveInnerBeanDefinition(BeanDefinitionValueResolver valueResolver, BeanDefinition innerBeanDefinition,
				BiConsumer<String, RootBeanDefinition> resolver) {
			valueResolver.resolveInnerBean(null, innerBeanDefinition, (name, rbd) -> {
				resolver.accept(name, rbd);
				return Void.class;
			});
		}

		private Class<?> resolveBeanType(AbstractBeanDefinition bd) {
			if (!bd.hasBeanClass()) {
				try {
					bd.resolveBeanClass(this.beanFactory.getBeanClassLoader());
				}
				catch (ClassNotFoundException ex) {
					// ignore
				}
			}
			return bd.getResolvableType().toClass();
		}
	}

}
