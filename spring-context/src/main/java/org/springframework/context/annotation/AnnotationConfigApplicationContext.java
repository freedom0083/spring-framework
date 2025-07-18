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

package org.springframework.context.annotation;

import java.util.Arrays;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.metrics.StartupStep;
import org.springframework.util.Assert;

/**
 * Standalone application context, accepting <em>component classes</em> as input &mdash;
 * in particular {@link Configuration @Configuration}-annotated classes, but also plain
 * {@link org.springframework.stereotype.Component @Component} types and JSR-330 compliant
 * classes using {@code jakarta.inject} annotations.
 *
 * <p>Allows for registering classes one by one using {@link #register(Class...)}
 * as well as for classpath scanning using {@link #scan(String...)}.
 *
 * <p>In case of multiple {@code @Configuration} classes, {@link Bean @Bean} methods
 * defined in later classes will override those defined in earlier classes. This can
 * be leveraged to deliberately override certain bean definitions via an extra
 * {@code @Configuration} class.
 *
 * <p>See {@link Configuration @Configuration}'s javadoc for usage examples.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @since 3.0
 * @see #register
 * @see #scan
 * @see AnnotatedBeanDefinitionReader
 * @see ClassPathBeanDefinitionScanner
 * @see org.springframework.context.support.GenericXmlApplicationContext
 */
public class AnnotationConfigApplicationContext extends GenericApplicationContext implements AnnotationConfigRegistry {

	private final AnnotatedBeanDefinitionReader reader;

	private final ClassPathBeanDefinitionScanner scanner;


	/**
	 * Create a new AnnotationConfigApplicationContext that needs to be populated
	 * through {@link #register} calls and then manually {@linkplain #refresh refreshed}.
	 */
	// TODO 默认构造器不会解析配置类，只会创建一个用于解析配置文件的 reader，以及扫描的 scanner。使用时需要自己手动执行 register() 解析
	//  配置类（并不解析其中的配置内容，如 @Bean 什么的），再调用 refresh() 启动整个容器
	public AnnotationConfigApplicationContext() {
		// TODO 用于收集 Spring 启动时的相关数据信息、处理时间等，目前有两个实现：
		//  1. DefaultStartupStep: 默认实现，什么也没做；
		//  2. FlightRecorderApplicationStartup: 利用JFR来对容器的启动进行监控；
		StartupStep createAnnotatedBeanDefReader = getApplicationStartup().start("spring.context.annotated-bean-reader.create");
		// TODO 初始化具有解析配置类能力的 reader, 初始化过程会调用 AnnotationConfigUtils#registerAnnotationConfigProcessors()
		//  同时注册 6 个 RootBeanDefinition (AbstractBeanDefinition) 用于处理注解的后处理器:
		//  1. ConfigurationClassPostProcessor: 实现了 BeanDefinitionRegistryPostProcessor, BeanRegistrationAotProcessor,
		//     BeanFactoryInitializationAotProcessor 接口。用来处理 @Configuration，@ComponmentScan 等注解。
		//     通过实现 postProcessBeanDefinitionRegistry() 方法, 来实现自定义的bean注册动作。@Configuration 配置类就是从这里解析配置类,
		//     将其中的 bean 解析为 BeanDefinition 后注册到容器中的
		//  2. AutowiredAnnotationBeanPostProcessor: 继承自 InstantiationAwareBeanPostProcessorAdapter, 同时实现了
		//     MergedBeanDefinitionPostProcessor 接口(用于自动装配, 可以处理 @Autowire, @Value, 以及 JSR-330 中的 @Inject)
		//  3. CommonAnnotationBeanPostProcessor: 提供生命周期管理( @PostConstruct, @PreDestroy ), 处理 JSR-250 规范的注解
		//     以及其他通用处理( @WebServiceRef, @EJB, @Resource )
		//  4. PersistenceAnnotationBeanPostProcessor: 处理JPA相关的注解, 如 @PersistenceContext, @PersistenceUnit 等
		//  5. EventListenerMethodProcessor: 处理事件监听器, 主要是对 @EventListener 注解的处理
		//  6. DefaultEventListenerFactory: 事件监听器工厂, 用于创建事件监听器
		this.reader = new AnnotatedBeanDefinitionReader(this);
		createAnnotatedBeanDefReader.end();
		// TODO 初始化一个使用默认过滤器的bean扫描器, 此扫描器用于后面解析过程时对指定包进行扫描，然后解析为 BeanDefinition 后注册到容器中
		//  默认的过滤器可以处理@Component, @Repository, @Controller和J2EE 6的@ManagedBean, JSR-330的@Named
		this.scanner = new ClassPathBeanDefinitionScanner(this);
	}

	/**
	 * Create a new AnnotationConfigApplicationContext with the given DefaultListableBeanFactory.
	 * @param beanFactory the DefaultListableBeanFactory instance to use for this context
	 */
	// TODO 根据指定的 DefaultListableBeanFactory 创建一个 AnnotationConfigApplicationContext 实例
	public AnnotationConfigApplicationContext(DefaultListableBeanFactory beanFactory) {
		super(beanFactory);
		// TODO 初始化解析带注解的bean的reader, 初始化过程会调用AnnotationConfigUtils#registerAnnotationConfigProcessors()
		//  整个过程会先初始化 DefaultListableBeanFactory，确保 BeanFactory 提供 @Order，@Lazy，@Qualifier，@Value 的支持，
		//  然后再注册以下 6 个后处理器:
		//  1. ConfigurationClassPostProcessor: 实现了BeanDefinitionRegistryPostProcessor接口
		//     通过实现 postProcessBeanDefinitionRegistry() 方法, 来实现自定义的bean注册动作
		//     @Configuration配置类就是从这里解析配置类, 将其中的bean注册到容器的
		//  2. AutowiredAnnotationBeanPostProcessor: 继承自InstantiationAwareBeanPostProcessorAdapter, 同时实现了
		//     MergedBeanDefinitionPostProcessor接口(用于自动装配, 可以处理@Autowire, @Value, 以及JSR-330中的@Inject)
		//  3. CommonAnnotationBeanPostProcessor: 用于处理J2EE 6的注解，提供生命周期管理的 @PostConstruct、@PreDestroy,
		//     以及其他通用处理(@WebServiceRef, @EJB, @Resource)
		//  4. PersistenceAnnotationBeanPostProcessor: 处理JPA相关的注解, 如@PersistenceContext, @PersistenceUnit等
		//  5. EventListenerMethodProcessor: 处理事件监听器, 主要是对@EventListener注解的处理
		//  6. DefaultEventListenerFactory: 事件监听器工厂, 用于创建事件监听器
		this.reader = new AnnotatedBeanDefinitionReader(this);
		// TODO 初始化一个使用默认过滤器的 bean 扫描器, 此扫描器用于后面解析过程时对指定包进行扫描
		//  默认的过滤器可以处理 @Component, @Repository, @Controller 和J2EE 6的 @ManagedBean, JSR-330 的 @Named
		this.scanner = new ClassPathBeanDefinitionScanner(this);
	}

	/**
	 * Create a new AnnotationConfigApplicationContext, deriving bean definitions
	 * from the given component classes and automatically refreshing the context.
	 * @param componentClasses one or more component classes &mdash; for example,
	 * {@link Configuration @Configuration} classes
	 */
	// TODO 处理直接传递进来的配置类
	public AnnotationConfigApplicationContext(Class<?>... componentClasses) {
		// TODO 开始做准备工作，初始化 beanFactory, 用来生成 BeanFactory 及解析配置文件的 AnnotatedBeanDefinitionReader,
		//  用来扫描给定目录，并解析其中内容的 ClassPathBeanDefinitionScanner
		this();
		// TODO 用默认构造器初始化好的 AnnotatedBeanDefinitionReader 来将配置类转化为 BeanDefinition 注册到容器中(不解析配置内容),
		//  解析方式同样是设置由 @Lazy, @Primary, @DependsOn, @Role, @Description 等指定的 value 值, 并将配置类注册到容器中.
		//  register 最终调用的是 AnnotationBeanDefinitionReader#doRegisterBean() 方法调用结束后，容器 BeanDefinitionMap
		//  中会有上面注册的 6 个后处理器，以及传进来的这些个 componentClasses。
		//   而具体的配置内容解析，是在 ConfigurationClassPostProcessor 后处理器的 postProcessBeanDefinitionRegistry() 中进行
		register(componentClasses);
		// TODO 开始注册bean, 同时完成单例bean的实例化
		refresh();
	}

	/**
	 * Create a new AnnotationConfigApplicationContext, scanning for components
	 * in the given packages, registering bean definitions for those components,
	 * and automatically refreshing the context.
	 * @param basePackages the packages to scan for component classes
	 */
	// TODO 处理传进来的是一个包路径的情况
	public AnnotationConfigApplicationContext(String... basePackages) {
		// TODO 开始做准备工作，初始化 beanFactory, 用来生成 BeanFactory 及解析配置文件的 AnnotatedBeanDefinitionReader,
		//  用来扫描给定目录，并解析其中内容的 ClassPathBeanDefinitionScanner
		this();
		// TODO 如果传入的是一个包路径, 则扫描包下所有符合要求的bean, 并注册到容器中，方法调用结束后，容器 BeanDefinitionMap 中会有上面
		//  注册的 6 个后处理器，以及路径下扫描到的这些内容
		scan(basePackages);
		// TODO 开始注册bean, 同时完成单例 bean 的实例化
		refresh();
	}


	/**
	 * Propagate the given custom {@code Environment} to the underlying
	 * {@link AnnotatedBeanDefinitionReader} and {@link ClassPathBeanDefinitionScanner}.
	 */
	@Override
	public void setEnvironment(ConfigurableEnvironment environment) {
		super.setEnvironment(environment);
		this.reader.setEnvironment(environment);
		this.scanner.setEnvironment(environment);
	}

	/**
	 * Provide a custom {@link BeanNameGenerator} for use with {@link AnnotatedBeanDefinitionReader}
	 * and/or {@link ClassPathBeanDefinitionScanner}, if any.
	 * <p>Default is {@link AnnotationBeanNameGenerator}.
	 * <p>Any call to this method must occur prior to calls to {@link #register(Class...)}
	 * and/or {@link #scan(String...)}.
	 * @see AnnotatedBeanDefinitionReader#setBeanNameGenerator
	 * @see ClassPathBeanDefinitionScanner#setBeanNameGenerator
	 * @see AnnotationBeanNameGenerator
	 * @see FullyQualifiedAnnotationBeanNameGenerator
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		this.reader.setBeanNameGenerator(beanNameGenerator);
		this.scanner.setBeanNameGenerator(beanNameGenerator);
		getBeanFactory().registerSingleton(
				AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR, beanNameGenerator);
	}

	/**
	 * Set the {@link ScopeMetadataResolver} to use for registered component classes.
	 * <p>The default is an {@link AnnotationScopeMetadataResolver}.
	 * <p>Any call to this method must occur prior to calls to {@link #register(Class...)}
	 * and/or {@link #scan(String...)}.
	 */
	public void setScopeMetadataResolver(ScopeMetadataResolver scopeMetadataResolver) {
		this.reader.setScopeMetadataResolver(scopeMetadataResolver);
		this.scanner.setScopeMetadataResolver(scopeMetadataResolver);
	}


	//---------------------------------------------------------------------
	// Implementation of AnnotationConfigRegistry
	//---------------------------------------------------------------------

	/**
	 * Register one or more component classes to be processed.
	 * <p>Note that {@link #refresh()} must be called in order for the context
	 * to fully process the new classes.
	 * @param componentClasses one or more component classes &mdash; for example,
	 * {@link Configuration @Configuration} classes
	 * @see #scan(String...)
	 * @see #refresh()
	 */
	@Override
	// TODO 调用reader解析配置类，仅仅是将配置类解析为 BeanDefinition，并不解析其中的内容
	public void register(Class<?>... componentClasses) {
		Assert.notEmpty(componentClasses, "At least one component class must be specified");
		StartupStep registerComponentClass = getApplicationStartup().start("spring.context.component-classes.register")
				.tag("classes", () -> Arrays.toString(componentClasses));
		// TODO 开始解析配置类并注册到容器中
		this.reader.register(componentClasses);
		registerComponentClass.end();
	}

	/**
	 * Perform a scan within the specified base packages.
	 * <p>Note that {@link #refresh()} must be called in order for the context
	 * to fully process the new classes.
	 * @param basePackages the packages to scan for component classes
	 * @see #register(Class...)
	 * @see #refresh()
	 */
	@Override
	public void scan(String... basePackages) {
		Assert.notEmpty(basePackages, "At least one base package must be specified");
		StartupStep scanPackages = getApplicationStartup().start("spring.context.base-packages.scan")
				.tag("packages", () -> Arrays.toString(basePackages));
		// TODO 包扫描，同时将包下所有符合要求的类注册到容器, 然后返回本次注册的bean数量, 只是进行注册, 并没有实例化
		//  实现在ClassPathBeanDefinitionScanner#doScan()中
		this.scanner.scan(basePackages);
		scanPackages.end();
	}


	//---------------------------------------------------------------------
	// Adapt superclass registerBean calls to AnnotatedBeanDefinitionReader
	//---------------------------------------------------------------------

	@Override
	public <T> void registerBean(@Nullable String beanName, Class<T> beanClass,
			@Nullable Supplier<T> supplier, BeanDefinitionCustomizer... customizers) {

		this.reader.registerBean(beanClass, beanName, supplier, customizers);
	}

}
