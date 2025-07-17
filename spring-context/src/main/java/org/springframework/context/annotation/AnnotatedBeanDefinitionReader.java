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

import java.lang.annotation.Annotation;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.Assert;

/**
 * Convenient adapter for programmatic registration of bean classes.
 *
 * <p>This is an alternative to {@link ClassPathBeanDefinitionScanner}, applying
 * the same resolution of annotations but for explicitly registered classes only.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @author Yanming Zhou
 * @since 3.0
 * @see AnnotationConfigApplicationContext#register
 */
// TODO 对带有注解的BeanDefinition进行注册
public class AnnotatedBeanDefinitionReader {
	// TODO BeanDefinition注册中心
	private final BeanDefinitionRegistry registry;

	private BeanNameGenerator beanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	private ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();
	// TODO 处理@Condition注解的处理器
	private ConditionEvaluator conditionEvaluator;


	/**
	 * Create a new {@code AnnotatedBeanDefinitionReader} for the given registry.
	 * <p>If the registry is {@link EnvironmentCapable}, for example, is an {@code ApplicationContext},
	 * the {@link Environment} will be inherited, otherwise a new
	 * {@link StandardEnvironment} will be created and used.
	 * @param registry the {@code BeanFactory} to load bean definitions into,
	 * in the form of a {@code BeanDefinitionRegistry}
	 * @see #AnnotatedBeanDefinitionReader(BeanDefinitionRegistry, Environment)
	 * @see #setEnvironment(Environment)
	 */
	public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry) {
		this(registry, getOrCreateEnvironment(registry));
	}

	/**
	 * Create a new {@code AnnotatedBeanDefinitionReader} for the given registry,
	 * using the given {@link Environment}.
	 * @param registry the {@code BeanFactory} to load bean definitions into,
	 * in the form of a {@code BeanDefinitionRegistry}
	 * @param environment the {@code Environment} to use when evaluating bean definition
	 * profiles.
	 * @since 3.1
	 */
	public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry, Environment environment) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		Assert.notNull(environment, "Environment must not be null");
		this.registry = registry;
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, null);
		// TODO 首先初始化 DefaultListableBeanFactory，确保 BeanFactory 提供 @Order，@Lazy，@Qualifier，@Value 的支持，
		//  然后注册以下后处理器：
		//   1. ConfigurationClassPostProcessor：用于处理 @Configuration 注解的后处理器，解析所有 @Bean 注解的方法
		//   2. AutowiredAnnotationBeanPostProcessor：用于处理 @Autowired、@Value 等注解的后处理器
		//   3. CommonAnnotationBeanPostProcessor：用于处理J2EE 6的注解，提供生命周期管理的 @PostConstruct、@PreDestroy,
		//		以及其他通用处理(@WebServiceRef, @EJB, @Resource)
		//   4. PersistenceAnnotationBeanPostProcessor：用于处理 JPA 的注解，如 @PersistenceContext、@PersistenceUnit 等
		//   5. EventListenerMethodProcessor：用于处理 @EventListener 注解的后处理器
		//   6. DefaultEventListenerFactory：用于创建事件监听器的工厂
		AnnotationConfigUtils.registerAnnotationConfigProcessors(this.registry);
	}


	/**
	 * Get the BeanDefinitionRegistry that this reader operates on.
	 */
	public final BeanDefinitionRegistry getRegistry() {
		return this.registry;
	}

	/**
	 * Set the {@code Environment} to use when evaluating whether
	 * {@link Conditional @Conditional}-annotated component classes should be registered.
	 * <p>The default is a {@link StandardEnvironment}.
	 */
	public void setEnvironment(Environment environment) {
		this.conditionEvaluator = new ConditionEvaluator(this.registry, environment, null);
	}

	/**
	 * Set the {@code BeanNameGenerator} to use for detected bean classes.
	 * <p>The default is a {@link AnnotationBeanNameGenerator}.
	 */
	public void setBeanNameGenerator(@Nullable BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator =
				(beanNameGenerator != null ? beanNameGenerator : AnnotationBeanNameGenerator.INSTANCE);
	}

	/**
	 * Set the {@code ScopeMetadataResolver} to use for registered component classes.
	 * <p>The default is an {@link AnnotationScopeMetadataResolver}.
	 */
	public void setScopeMetadataResolver(@Nullable ScopeMetadataResolver scopeMetadataResolver) {
		this.scopeMetadataResolver =
				(scopeMetadataResolver != null ? scopeMetadataResolver : new AnnotationScopeMetadataResolver());
	}


	/**
	 * Register one or more component classes to be processed.
	 * <p>Calls to {@code register} are idempotent; adding the same
	 * component class more than once has no additional effect.
	 * @param componentClasses one or more component classes,
	 * for example, {@link Configuration @Configuration} classes：传进来的要解析的配置类
	 */
	public void register(Class<?>... componentClasses) {
		for (Class<?> componentClass : componentClasses) {
			registerBean(componentClass);
		}
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean 传进来的要解析的配置类
	 */
	public void registerBean(Class<?> beanClass) {
		doRegisterBean(beanClass, null, null, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @since 5.2
	 */
	public void registerBean(Class<?> beanClass, @Nullable String name) {
		doRegisterBean(beanClass, name, null, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param qualifiers specific qualifier annotations to consider,
	 * in addition to qualifiers at the bean class level
	 */
	@SuppressWarnings("unchecked")
	public void registerBean(Class<?> beanClass, Class<? extends Annotation>... qualifiers) {
		doRegisterBean(beanClass, null, qualifiers, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param qualifiers specific qualifier annotations to consider,
	 * in addition to qualifiers at the bean class level
	 */
	@SuppressWarnings("unchecked")
	public void registerBean(Class<?> beanClass, @Nullable String name,
			Class<? extends Annotation>... qualifiers) {

		doRegisterBean(beanClass, name, qualifiers, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations, using the given supplier for obtaining a new
	 * instance (possibly declared as a lambda expression or method reference).
	 * @param beanClass the class of the bean
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @since 5.0
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable Supplier<T> supplier) {
		doRegisterBean(beanClass, null, null, supplier, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations, using the given supplier for obtaining a new
	 * instance (possibly declared as a lambda expression or method reference).
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @since 5.0
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable String name, @Nullable Supplier<T> supplier) {
		doRegisterBean(beanClass, name, null, supplier, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @param customizers one or more callbacks for customizing the factory's
	 * {@link BeanDefinition}, for example, setting a lazy-init or primary flag
	 * @since 5.2
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable String name, @Nullable Supplier<T> supplier,
			BeanDefinitionCustomizer... customizers) {

		doRegisterBean(beanClass, name, null, supplier, customizers);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean 传进来的要解析的配置类
	 * @param name an explicit name for the bean
	 * @param qualifiers specific qualifier annotations to consider, if any,
	 * in addition to qualifiers at the bean class level 用于处理@Qualifier的情况, 这个参数好像没用过
	 * in addition to qualifiers at the bean class level
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @param customizers one or more callbacks for customizing the factory's
	 * {@link BeanDefinition}, for example, setting a lazy-init or primary flag
	 * @since 5.0
	 */
	// TODO
	private <T> void doRegisterBean(Class<T> beanClass, @Nullable String name,
			Class<? extends Annotation> @Nullable [] qualifiers, @Nullable Supplier<T> supplier,
			BeanDefinitionCustomizer @Nullable [] customizers) {
		// TODO 将传入的配置类转化为 BeanDefinition, 转化过程会提取配置类内注解的元数据并放到 AnnotationMetadata
		AnnotatedGenericBeanDefinition abd = new AnnotatedGenericBeanDefinition(beanClass);
		// TODO 判断一下是否满足 @Condition 要求的条件，以前当前是否在解析配置或注册 Bean 的阶段, 不满足的就不需要进行注入了
		if (this.conditionEvaluator.shouldSkip(abd.getMetadata())) {
			return;
		}

		abd.setAttribute(ConfigurationClassUtils.CANDIDATE_ATTRIBUTE, Boolean.TRUE);
		// TODO 创建bean实例时, 可以通过 Supplier 进行创建. 会在 AbstractAutowireCapableBeanFactory#createBeanInstance() 方法中进行调用
		abd.setInstanceSupplier(supplier);
		// TODO 解析 @Scope 的值做为 Bean 的作用域, 为空时默认为singleton
		ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(abd);
		abd.setScope(scopeMetadata.getScopeName());
		// TODO 生成 Bean 的名字，先尝试从注解中提取（ Component 的 value 属性，以及其他原型注解的 value 属性），无法取得时，直接用小写
		//  开头的类名做为名字
		String beanName = (name != null ? name : this.beanNameGenerator.generateBeanName(abd, this.registry));
		// TODO 根据 @Lazy, @Primary, @DependsOn, @Role, @Description 这五个注解中的value来设置 bean 中对应的方法
		//  1. @Lazy：指定 Bean 是否为懒加载，默认为 false 表示立即初始化，为 true 时表示首次使用时初始化
		//  2. @Primary：自动装配时出现多个 Bean 候选时，做为首选，否则会抛出异常
		//  3. @DependsOn：定义 Bean 初始化顺序
		//  4. @Role：用于用户自定义 Bean 其 value 是 int 类型，表示 Bean 在应用中的角色，默认为 0
		//  5. @Description：用于描述 Bean
		AnnotationConfigUtils.processCommonDefinitionAnnotations(abd);
		// TODO 解析 @Qualifier 注解, @Autowired 自动装配时用来区分相同类型的多个 Bean，会变为 byName 方式注入
		if (qualifiers != null) {
			for (Class<? extends Annotation> qualifier : qualifiers) {
				if (Primary.class == qualifier) {
					// TODO 如果包含 @Primary 注解, 设置 abd 有首选项
					abd.setPrimary(true);
				}
				else if (Fallback.class == qualifier) {
					abd.setFallback(true);
				}
				else if (Lazy.class == qualifier) {
					// TODO 如果包含 @Lazy 注解, 设置 abd 使用懒加载
					abd.setLazyInit(true);
				}
				else {
					// TODO 其他情况, 将其添加到 abd 的 qualifiers 缓存中
					abd.addQualifier(new AutowireCandidateQualifier(qualifier));
				}
			}
		}
		// TODO 自定义bean注册, 在手动进行注册时会用到
		//  applicationContext.registerBean(UserService.class, () -> new UserService());
		if (customizers != null) {
			for (BeanDefinitionCustomizer customizer : customizers) {
				customizer.customize(abd);
			}
		}

		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(abd, beanName);
		// TODO 创建一个代理对象, 当代理模式设置为 NO 时, 直接返回 definitionHolder 本身，需要创建代理时，最终返回的会是一个代理 DefinitionHolder
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
		// TODO 将解析后的bean注册到容器中
		BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, this.registry);
	}


	/**
	 * Get the Environment from the given registry if possible, otherwise return a new
	 * StandardEnvironment.
	 */
	private static Environment getOrCreateEnvironment(BeanDefinitionRegistry registry) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		if (registry instanceof EnvironmentCapable environmentCapable) {
			return environmentCapable.getEnvironment();
		}
		return new StandardEnvironment();
	}

}
