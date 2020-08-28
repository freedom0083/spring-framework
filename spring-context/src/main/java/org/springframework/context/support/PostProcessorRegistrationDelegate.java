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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
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
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		Set<String> processedBeans = new HashSet<>();
		// TODO 判断beanFactory是否为BeanDefinitionRegistry类型
		if (beanFactory instanceof BeanDefinitionRegistry) {
			// TODO 在容器进行初始化过程时, AbstractApplicationContext#refresh()方法中调用了obtainFreshBeanFactory()方法生成
			//  了一个DefaultListableBeanFactory类型的BeanFactory放到容器的beanFactory属性中. 由于DefaultListableBeanFactory
			//  实现了BeanDefinitionRegistry接口, 因此在初始化过程中会直接进入到这里.
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			// TODO 存放普通BeanFactoryPostProcessor的缓存
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			// TODO 存放BeanDefinitionRegistryPostProcessor的缓存
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();
			// TODO beanFactoryPostProcessors中所有的对象都是在ApplicationContext#addBeanFactoryPostProcessor()手动注册进去的
			//  如果没有进行过手动注册, beanFactoryPostProcessors一般情况下都是空的, 这里区分了BeanDefinitionRegistryPostProcessor
			//  和普通BeanFactoryPostProcessor
			//    1. BeanDefinitionRegistryPostProcessor: 通过postProcessBeanDefinitionRegistry()方法实现自定义类加载
			//    2. BeanFactoryPostProcessor: 普通的容器级后处理器
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					// TODO 处理手动注册的BeanFactoryPostProcessor后处理器是BeanDefinitionRegistryPostProcessor类型的情况
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					// TODO 调用后处理器的postProcessBeanDefinitionRegistry()执行自定义bean注册动作
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
		// TODO 取得所有的BeanPostProcessor后处理器名
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
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
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
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
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
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

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
