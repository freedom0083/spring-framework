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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.DeferredImportSelector.Group;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PropertySourceDescriptor;
import org.springframework.core.io.support.PropertySourceProcessor;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Parses a {@link Configuration} class definition, populating a collection of
 * {@link ConfigurationClass} objects (parsing a single Configuration class may result in
 * any number of ConfigurationClass objects because one Configuration class may import
 * another using the {@link Import} annotation).
 *
 * <p>This class helps separate the concern of parsing the structure of a Configuration class
 * from the concern of registering BeanDefinition objects based on the content of that model
 * (except {@code @ComponentScan} annotations which need to be registered immediately).
 *
 * <p>This ASM-based implementation avoids reflection and eager class loading in order to
 * interoperate effectively with lazy class loading in a Spring ApplicationContext.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @author Daeho Kwon
 * @since 3.0
 * @see ConfigurationClassBeanDefinitionReader
 */
class ConfigurationClassParser {

	private static final Predicate<String> DEFAULT_EXCLUSION_FILTER = className ->
			(className.startsWith("java.lang.annotation.") || className.startsWith("org.springframework.stereotype."));

	private static final Predicate<Condition> REGISTER_BEAN_CONDITION_FILTER = condition ->
			(condition instanceof ConfigurationCondition configurationCondition &&
				ConfigurationPhase.REGISTER_BEAN.equals(configurationCondition.getConfigurationPhase()));

	private static final Comparator<DeferredImportSelectorHolder> DEFERRED_IMPORT_COMPARATOR =
			(o1, o2) -> AnnotationAwareOrderComparator.INSTANCE.compare(o1.getImportSelector(), o2.getImportSelector());


	private final Log logger = LogFactory.getLog(getClass());

	private final MetadataReaderFactory metadataReaderFactory;

	private final ProblemReporter problemReporter;

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final @Nullable PropertySourceRegistry propertySourceRegistry;

	private final BeanDefinitionRegistry registry;

	private final ComponentScanAnnotationParser componentScanParser;

	private final ConditionEvaluator conditionEvaluator;

	private final Map<ConfigurationClass, ConfigurationClass> configurationClasses = new LinkedHashMap<>();

	private final MultiValueMap<String, ConfigurationClass> knownSuperclasses = new LinkedMultiValueMap<>();

	private final ImportStack importStack = new ImportStack();

	private final DeferredImportSelectorHandler deferredImportSelectorHandler = new DeferredImportSelectorHandler();

	private final SourceClass objectSourceClass = new SourceClass(Object.class);


	/**
	 * Create a new {@link ConfigurationClassParser} instance that will be used
	 * to populate the set of configuration classes.
	 */
	public ConfigurationClassParser(MetadataReaderFactory metadataReaderFactory,
			ProblemReporter problemReporter, Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator componentScanBeanNameGenerator, BeanDefinitionRegistry registry) {

		this.metadataReaderFactory = metadataReaderFactory;
		this.problemReporter = problemReporter;
		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.propertySourceRegistry = (this.environment instanceof ConfigurableEnvironment ce ?
				new PropertySourceRegistry(new PropertySourceProcessor(ce, this.resourceLoader)) : null);
		this.registry = registry;
		this.componentScanParser = new ComponentScanAnnotationParser(
				environment, resourceLoader, componentScanBeanNameGenerator, registry);
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}

	// TODO 由 ConfigurationClassPostProcessor 的 processConfigBeanDefinitions() 方法进入
	public void parse(Set<BeanDefinitionHolder> configCandidates) {
		for (BeanDefinitionHolder holder : configCandidates) {
			BeanDefinition bd = holder.getBeanDefinition();
			try {
				ConfigurationClass configClass;
				if (bd instanceof AnnotatedBeanDefinition annotatedBeanDef) {
					// TODO 解析 AnnotatedBeanDefinition
					configClass = parse(annotatedBeanDef, holder.getBeanName());
				}
				else if (bd instanceof AbstractBeanDefinition abstractBeanDef && abstractBeanDef.hasBeanClass()) {
					// TODO 解析 AbstractBeanDefinition
					configClass = parse(abstractBeanDef.getBeanClass(), holder.getBeanName());
				}
				else {
					// TODO 解析其他情况
					configClass = parse(bd.getBeanClassName(), holder.getBeanName());
				}

				// Downgrade to lite (no enhancement) in case of no instance-level @Bean methods.
				if (!configClass.getMetadata().isAbstract() && !configClass.hasNonStaticBeanMethods() &&
						ConfigurationClassUtils.CONFIGURATION_CLASS_FULL.equals(
								bd.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE))) {
					bd.setAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE,
							ConfigurationClassUtils.CONFIGURATION_CLASS_LITE);
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to parse configuration class [" + bd.getBeanClassName() + "]", ex);
			}
		}
		// TODO 配置类解析完毕后, 再解析由 @DeferredImport 指定的配置类
		this.deferredImportSelectorHandler.process();
	}

	private ConfigurationClass parse(AnnotatedBeanDefinition beanDef, String beanName) {
		// TODO 把配置文件封装成一个 ConfigurationClass
		ConfigurationClass configClass = new ConfigurationClass(
				beanDef.getMetadata(), beanName, (beanDef instanceof ScannedGenericBeanDefinition));
		processConfigurationClass(configClass, DEFAULT_EXCLUSION_FILTER);
		return configClass;
	}

	private ConfigurationClass parse(Class<?> clazz, String beanName) {
		ConfigurationClass configClass = new ConfigurationClass(clazz, beanName);
		processConfigurationClass(configClass, DEFAULT_EXCLUSION_FILTER);
		return configClass;
	}

	final ConfigurationClass parse(@Nullable String className, String beanName) throws IOException {
		Assert.notNull(className, "No bean class name for configuration class bean definition");
		// TODO 这里得到了一个SimpleMetadataReader, 用ASM方式进行反射. 元数据信息用SimpleAnnotationMetadataReadingVisitor得到
		// TODO 这里得到了一个SimpleMetadataReader, 用ASM方式进行反射. 元数据信息用SimpleAnnotationMetadataReadingVisitor得到
		MetadataReader reader = this.metadataReaderFactory.getMetadataReader(className);
		ConfigurationClass configClass = new ConfigurationClass(reader, beanName);
		// TODO 开始解析配置文件, 这里就用上面创建的reader的元数据信息和资源文件来创建configuration类, 这个类里包含了所有@Bean注解的方法集
		//  import的其他配置类集合, import的资源类集合, 要跳过的方法, ImportBeanDefinitionRegistrar集合等
		processConfigurationClass(configClass, DEFAULT_EXCLUSION_FILTER);
		return configClass;
	}

	/**
	 * Validate each {@link ConfigurationClass} object.
	 * @see ConfigurationClass#validate
	 */
	void validate() {
		for (ConfigurationClass configClass : this.configurationClasses.keySet()) {
			configClass.validate(this.problemReporter);
		}
	}

	Set<ConfigurationClass> getConfigurationClasses() {
		return this.configurationClasses.keySet();
	}

	List<PropertySourceDescriptor> getPropertySourceDescriptors() {
		return (this.propertySourceRegistry != null ? this.propertySourceRegistry.getDescriptors() :
				Collections.emptyList());
	}

	ImportRegistry getImportRegistry() {
		return this.importStack;
	}


	protected void processConfigurationClass(ConfigurationClass configClass, Predicate<String> filter) {
		if (this.conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.PARSE_CONFIGURATION)) {
			return;
		}

		ConfigurationClass existingClass = this.configurationClasses.get(configClass);
		if (existingClass != null) {
			// TODO 配置文件被解析过时
			if (configClass.isImported()) {
				// TODO 对 import 进行处理
				if (existingClass.isImported()) {
					// TODO 把所有解析过的 import 配置合并
					existingClass.mergeImportedBy(configClass);
				}
				// Otherwise ignore new imported config class; existing non-imported class overrides it.
				return;
			}
			else if (configClass.isScanned()) {
				String beanName = configClass.getBeanName();
				if (StringUtils.hasLength(beanName) && this.registry.containsBeanDefinition(beanName)) {
					this.registry.removeBeanDefinition(beanName);
				}
				// An implicitly scanned bean definition should not override an explicit import.
				return;
			}
			else {
				// Explicit bean definition found, probably replacing an import.
				// Let's remove the old one and go with the new one.
				this.configurationClasses.remove(configClass);
				removeKnownSuperclass(configClass.getMetadata().getClassName(), false);
			}
		}

		// Recursively process the configuration class and its superclass hierarchy.
		SourceClass sourceClass = null;
		try {
			sourceClass = asSourceClass(configClass, filter);
			do {
				// TODO 正式开始解析配置类
				sourceClass = doProcessConfigurationClass(configClass, sourceClass, filter);
			}
			while (sourceClass != null);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"I/O failure while processing configuration class [" + sourceClass + "]", ex);
		}

		// TODO 解析过的类放到缓存中
		this.configurationClasses.put(configClass, configClass);
	}

	/**
	 * Apply processing and build a complete {@link ConfigurationClass} by reading the
	 * annotations, members and methods from the source class. This method can be called
	 * multiple times as relevant sources are discovered.
	 * @param configClass the configuration class being build
	 * @param sourceClass a source class
	 * @return the superclass, or {@code null} if none found or previously processed
	 */
	protected final @Nullable SourceClass doProcessConfigurationClass(
			ConfigurationClass configClass, SourceClass sourceClass, Predicate<String> filter)
			throws IOException {

		if (configClass.getMetadata().isAnnotated(Component.class.getName())) {
			// Recursively process any member (nested) classes first
			// TODO 处理@Component
			processMemberClasses(configClass, sourceClass, filter);
		}

		// Process any @PropertySource annotations
		for (AnnotationAttributes propertySource : AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), org.springframework.context.annotation.PropertySource.class,
				PropertySources.class, true)) {
			// TODO 处理 @PropertySources, 加载 properties
			if (this.propertySourceRegistry != null) {
				this.propertySourceRegistry.processPropertySource(propertySource);
			}
			else {
				logger.info("Ignoring @PropertySource annotation on [" + sourceClass.getMetadata().getClassName() +
						"]. Reason: Environment must implement ConfigurableEnvironment");
			}
		}

		// Search for locally declared @ComponentScan annotations first.
		// TODO 准备开始扫描
		Set<AnnotationAttributes> componentScans = AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), ComponentScan.class, ComponentScans.class,
				MergedAnnotation::isDirectlyPresent);

		// Fall back to searching for @ComponentScan meta-annotations (which indirectly
		// includes locally declared composed annotations).
		if (componentScans.isEmpty()) {
			componentScans = AnnotationConfigUtils.attributesForRepeatable(sourceClass.getMetadata(),
					ComponentScan.class, ComponentScans.class, MergedAnnotation::isMetaPresent);
		}

		if (!componentScans.isEmpty()) {
			List<Condition> registerBeanConditions = collectRegisterBeanConditions(configClass);
			if (!registerBeanConditions.isEmpty()) {
				throw new ApplicationContextException(
						"Component scan for configuration class [%s] could not be used with conditions in REGISTER_BEAN phase: %s"
								.formatted(configClass.getMetadata().getClassName(), registerBeanConditions));
			}
			// TODO 遇到 @ComponentScans 时, 使用 ClassPathBeanDefinitionScanner 对 @ComponentScans 指定的包开始进行扫描
			for (AnnotationAttributes componentScan : componentScans) {
				// The config class is annotated with @ComponentScan -> perform the scan immediately
				// TODO 这里开始解析 @ComponentScan 内指定位置的类, 然后注册到容器中, 这时 bean 的属性已经被填充好了
				//  如果设置了代理机制, 则返回的会是代理类的 holder
				Set<BeanDefinitionHolder> scannedBeanDefinitions =
						this.componentScanParser.parse(componentScan, sourceClass.getMetadata().getClassName());
				// Check the set of scanned definitions for any further config classes and parse recursively if needed
				for (BeanDefinitionHolder holder : scannedBeanDefinitions) {
					BeanDefinition bdCand = holder.getBeanDefinition().getOriginatingBeanDefinition();
					if (bdCand == null) {
						bdCand = holder.getBeanDefinition();
					}
					if (ConfigurationClassUtils.checkConfigurationClassCandidate(bdCand, this.metadataReaderFactory)) {
						// TODO 如果已经被注册到容器中的bean有被注解为@Configuration的配置类时, 对其进行解析
						parse(bdCand.getBeanClassName(), holder.getBeanName());
					}
				}
			}
		}

		// Process any @Import annotations
		// TODO 处理@Import指定的类, 具体有哪些类是存储在getImports()返回的一个set中, 这个set包含了当前配置类, 及其包含的其他注解
		//  比如@EnableXXX中, 所有@Import指向的类
		processImports(configClass, sourceClass, getImports(sourceClass), filter, true);

		// Process any @ImportResource annotations
		// TODO 处理@ImportResource注解
		AnnotationAttributes importResource =
				AnnotationConfigUtils.attributesFor(sourceClass.getMetadata(), ImportResource.class);
		if (importResource != null) {
			// TODO 处理@ImportResource注解
			String[] resources = importResource.getStringArray("locations");
			Class<? extends BeanDefinitionReader> readerClass = importResource.getClass("reader");
			for (String resource : resources) {
				String resolvedResource = this.environment.resolveRequiredPlaceholders(resource);
				// TODO 处理完放到configClass的importedResources中
				//  ConfigurationClassBeanDefinitionReader的loadBeanDefinitionsForConfigurationClass()方法
				//  会用到@ImportResource指定的reader来解析并注册bean
				configClass.addImportedResource(resolvedResource, readerClass);
			}
		}

		// Process individual @Bean methods
		// TODO 开始处理@Bean注解的方法, 会得到一个由@Bean注解的方法集合, 并放到configClass的beanMethod集合中. 这里只是得到了所有
		//  由@Bean标注的方法, 具体解析是在后面由reader注册@Bean方法时做的
		Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(sourceClass);
		for (MethodMetadata methodMetadata : beanMethods) {
			if (methodMetadata.isAnnotated("kotlin.jvm.JvmStatic") && !methodMetadata.isStatic()) {
				continue;
			}
			configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
		}

		// Process default methods on interfaces
		// TODO 配置类有实现接口时, 把接口中的default方法也注册到configClass的beanMethod集合中, 这个是对Java8后用的
		processInterfaces(configClass, sourceClass);

		// Process superclass, if any
		// TODO 配置类还有父类时, 将其父类加入到缓存中, 并返回, 此时解析结束
		if (sourceClass.getMetadata().hasSuperClass()) {
			String superclass = sourceClass.getMetadata().getSuperClassName();
			if (superclass != null && !superclass.startsWith("java")) {
				boolean superclassKnown = this.knownSuperclasses.containsKey(superclass);
				this.knownSuperclasses.add(superclass, configClass);
				if (!superclassKnown) {
					// Superclass found, return its annotation metadata and recurse
					return sourceClass.getSuperClass();
				}
			}
		}

		// No superclass -> processing is complete
		// TODO 没有父类时, 直接返回null
		return null;
	}

	/**
	 * Register member (nested) classes that happen to be configuration classes themselves.
	 */
	private void processMemberClasses(ConfigurationClass configClass, SourceClass sourceClass,
			Predicate<String> filter) throws IOException {

		Collection<SourceClass> memberClasses = sourceClass.getMemberClasses();
		if (!memberClasses.isEmpty()) {
			List<SourceClass> candidates = new ArrayList<>(memberClasses.size());
			for (SourceClass memberClass : memberClasses) {
				if (ConfigurationClassUtils.isConfigurationCandidate(memberClass.getMetadata()) &&
						!memberClass.getMetadata().getClassName().equals(configClass.getMetadata().getClassName())) {
					// TODO 把带有 @Bean 注解的类加入到候选中
					candidates.add(memberClass);
				}
			}
			OrderComparator.sort(candidates);
			for (SourceClass candidate : candidates) {
				if (this.importStack.contains(configClass)) {
					this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
				}
				else {
					// TODO 挨个处理配置类, 把当前正在处理的类入栈保存
					this.importStack.push(configClass);
					try {
						// TODO 然后开始递归解析
						processConfigurationClass(candidate.asConfigClass(configClass), filter);
					}
					finally {
						// TODO 直到当前配置类全部处理完成后, 弹出
						this.importStack.pop();
					}
				}
			}
		}
	}

	/**
	 * Register default methods on interfaces implemented by the configuration class.
	 */
	private void processInterfaces(ConfigurationClass configClass, SourceClass sourceClass) throws IOException {
		for (SourceClass ifc : sourceClass.getInterfaces()) {
			Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(ifc);
			for (MethodMetadata methodMetadata : beanMethods) {
				if (!methodMetadata.isAbstract()) {
					// A default method or other concrete method on a Java 8+ interface...
					// TODO 将接口中的default方法也当做beanMethod
					configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
				}
			}
			// TODO 递归所有接口
			processInterfaces(configClass, ifc);
		}
	}

	/**
	 * Retrieve the metadata for all <code>@Bean</code> methods.
	 */
	private Set<MethodMetadata> retrieveBeanMethodMetadata(SourceClass sourceClass) {
		AnnotationMetadata original = sourceClass.getMetadata();
		// TODO 这里得到的是一个由@Bean注解的方法集合
		Set<MethodMetadata> beanMethods = original.getAnnotatedMethods(Bean.class.getName());
		// TODO 使用标准反射时, 再用ASM处理一次元数据信息
		if (beanMethods.size() > 1 && original instanceof StandardAnnotationMetadata) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			try {
				// TODO 这边也是得到了一个SimpleMetadataReader, 后面用ASM方式进行动态处理
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(original.getClassName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Bean.class.getName());
				if (asmMethods.size() >= beanMethods.size()) {
					Set<MethodMetadata> candidateMethods = new LinkedHashSet<>(beanMethods);
					Set<MethodMetadata> selectedMethods = CollectionUtils.newLinkedHashSet(asmMethods.size());
					for (MethodMetadata asmMethod : asmMethods) {
						for (Iterator<MethodMetadata> it = candidateMethods.iterator(); it.hasNext();) {
							MethodMetadata beanMethod = it.next();
							if (beanMethod.getMethodName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(beanMethod);
								it.remove();
								break;
							}
						}
					}
					if (selectedMethods.size() == beanMethods.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						beanMethods = selectedMethods;
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Bean method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return beanMethods;
	}

	/**
	 * Remove known superclasses for the given removed class, potentially replacing
	 * the superclass exposure on a different config class with the same superclass.
	 */
	private void removeKnownSuperclass(String removedClass, boolean replace) {
		String replacedSuperclass = null;
		ConfigurationClass replacingClass = null;

		Iterator<Map.Entry<String, List<ConfigurationClass>>> it = this.knownSuperclasses.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, List<ConfigurationClass>> entry = it.next();
			if (entry.getValue().removeIf(configClass -> configClass.getMetadata().getClassName().equals(removedClass))) {
				if (entry.getValue().isEmpty()) {
					it.remove();
				}
				else if (replace && replacingClass == null) {
					replacedSuperclass = entry.getKey();
					replacingClass = entry.getValue().get(0);
				}
			}
		}

		if (replacingClass != null) {
			try {
				SourceClass sourceClass = asSourceClass(replacingClass, DEFAULT_EXCLUSION_FILTER).getSuperClass();
				while (!sourceClass.getMetadata().getClassName().equals(replacedSuperclass) &&
						sourceClass.getMetadata().getSuperClassName() != null) {
					sourceClass = sourceClass.getSuperClass();
				}
				do {
					sourceClass = doProcessConfigurationClass(replacingClass, sourceClass, DEFAULT_EXCLUSION_FILTER);
				}
				while (sourceClass != null);
			}
			catch (IOException ex) {
				throw new BeanDefinitionStoreException(
						"I/O failure while removing configuration class [" + removedClass + "]", ex);
			}
		}
	}

	/**
	 * Returns {@code @Import} classes, considering all meta-annotations.
	 */
	private Set<SourceClass> getImports(SourceClass sourceClass) throws IOException {
		Set<SourceClass> imports = new LinkedHashSet<>();
		collectImports(sourceClass, imports, new HashSet<>());
		return imports;
	}

	/**
	 * Recursively collect all declared {@code @Import} values. Unlike most
	 * meta-annotations it is valid to have several {@code @Import}s declared with
	 * different values; the usual process of returning values from the first
	 * meta-annotation on a class is not sufficient.
	 * <p>For example, it is common for a {@code @Configuration} class to declare direct
	 * {@code @Import}s in addition to meta-imports originating from an {@code @Enable}
	 * annotation.
	 * <p>As of Spring Framework 7.0, {@code @Import} annotations declared on interfaces
	 * implemented by the configuration class are also considered. This allows imports to
	 * be triggered indirectly via marker interfaces or shared base interfaces.
	 * @param sourceClass the class to search
	 * @param imports the imports collected so far
	 * @param visited used to track visited classes and interfaces to prevent infinite
	 * recursion
	 * @throws IOException if there is any problem reading metadata from the named class
	 */
	private void collectImports(SourceClass sourceClass, Set<SourceClass> imports, Set<SourceClass> visited)
			throws IOException {
		// TODO 判断一下配置类@Import的配置类是否已经访问过了, 这里对@Import指向的配置类只处理一次, 防止无限递归
		if (visited.add(sourceClass)) {
			for (SourceClass ifc : sourceClass.getInterfaces()) {
				collectImports(ifc, imports, visited);
			}
			for (SourceClass annotation : sourceClass.getAnnotations()) {
				String annName = annotation.getMetadata().getClassName();
				// TODO 挨个判断一下配置类的其他注解, 并进入其中查看是否还有内嵌的@Import注解
				if (!annName.equals(Import.class.getName())) {
					// TODO 如果有, 就递归进入, 解析其中可能出现的@Import注解
					collectImports(annotation, imports, visited);
				}
			}
			// TODO 返回的是配置类中, 以及其包含的其他注解中的@Import指向的所有类
			imports.addAll(sourceClass.getAnnotationAttributes(Import.class.getName(), "value"));
		}
	}

	private void processImports(ConfigurationClass configClass, SourceClass currentSourceClass,
			Collection<SourceClass> importCandidates, Predicate<String> filter, boolean checkForCircularImports) {

		if (importCandidates.isEmpty()) {
			return;
		}
		// TODO 看一下栈里是否有正在处理的配置类, 如果有表示当前处理的import内容目前正处于进行中, 这时需要提示个错误信息这个import正在处理中
		if (checkForCircularImports && isChainedImportOnStack(configClass)) {
			this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
		}
		else {
			// TODO import的配置类目前没有解析时, 先将其入栈保留现场, 然后开始进行解析
			this.importStack.push(configClass);
			try {
				// TODO 解析时, 会根据@Import中指定的不同selector进行区分, selector可以实现动态加载不同配置
				for (SourceClass candidate : importCandidates) {
					// TODO 指定的是ImportSelector时
					if (candidate.isAssignable(ImportSelector.class)) {
						// Candidate class is an ImportSelector -> delegate to it to determine imports
						Class<?> candidateClass = candidate.loadClass();
						ImportSelector selector = ParserStrategyUtils.instantiateClass(candidateClass, ImportSelector.class,
								this.environment, this.resourceLoader, this.registry);
						Predicate<String> selectorFilter = selector.getExclusionFilter();
						if (selectorFilter != null) {
							filter = filter.or(selectorFilter);
						}
						// TODO DeferredImportSelector表示的是要在@Configuration之后进行处理的import配置类
						//  会在配置类解析后再由DeferredImportSelectorHandler进行解析
						if (selector instanceof DeferredImportSelector deferredImportSelector) {
							this.deferredImportSelectorHandler.handle(configClass, deferredImportSelector);
						}
						else {
							// TODO 普通的ImportSelector这时就直接调用selectImports执行自定义的选择器来选择需要进行解析的配置类了
							//  自己实现时, 只需要实现selectImports()即可
							String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata());
							Collection<SourceClass> importSourceClasses = asSourceClasses(importClassNames, filter);
							// TODO 递归解析import
							processImports(configClass, currentSourceClass, importSourceClasses, filter, false);
						}
					}
					// TODO 指定的是ImportBeanDefinitionRegistrar时, 将其加入到配置文件的缓存里, 此接口只有默认方法, 因为可以使用默认实现
					//  实现ImportBeanDefinitionRegistrar接口的类不会被注册到容器中, 他是用来注册其指定的beanDefinition
					//  重写其中的registerBeanDefinitions()方法可以实现自定义的方式将beanDefinition注册到容器中
					else if (candidate.isAssignable(BeanRegistrar.class)) {
						Class<?> candidateClass = candidate.loadClass();
						BeanRegistrar registrar = (BeanRegistrar) BeanUtils.instantiateClass(candidateClass);
						AnnotationMetadata metadata = currentSourceClass.getMetadata();
						if (registrar instanceof ImportAware importAware) {
							importAware.setImportMetadata(metadata);
						}
						// TODO 将实现接口的类放入importBeanDefinitionRegistrars的Map中
						configClass.addBeanRegistrar(metadata.getClassName(), registrar);
					}
					else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) {
						// Candidate class is an ImportBeanDefinitionRegistrar ->
						// delegate to it to register additional bean definitions
						Class<?> candidateClass = candidate.loadClass();
						ImportBeanDefinitionRegistrar registrar =
								ParserStrategyUtils.instantiateClass(candidateClass, ImportBeanDefinitionRegistrar.class,
										this.environment, this.resourceLoader, this.registry);
						// TODO 将实现接口的类放入importBeanDefinitionRegistrars的Map中
						configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata());
					}
					else {
						// Candidate class not an ImportSelector or ImportBeanDefinitionRegistrar ->
						// process it as an @Configuration class
						// TODO 走到这时, 就应该是递归的最后一层了, 开始处理配置类了, 元数据都会放到缓存, 准备后面的处理工作
						this.importStack.registerImport(
								currentSourceClass.getMetadata(), candidate.getMetadata().getClassName());
						// TODO 继续处理@Configuration注解的配置类
						processConfigurationClass(candidate.asConfigClass(configClass), filter);
					}
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to process import candidates for configuration class [" +
						configClass.getMetadata().getClassName() + "]: " + ex.getMessage(), ex);
			}
			finally {
				// TODO 处理完一个类后弹出, 恢复现场
				this.importStack.pop();
			}
		}
	}

	private boolean isChainedImportOnStack(ConfigurationClass configClass) {
		if (this.importStack.contains(configClass)) {
			String configClassName = configClass.getMetadata().getClassName();
			AnnotationMetadata importingClass = this.importStack.getImportingClassFor(configClassName);
			while (importingClass != null) {
				if (configClassName.equals(importingClass.getClassName())) {
					return true;
				}
				importingClass = this.importStack.getImportingClassFor(importingClass.getClassName());
			}
		}
		return false;
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link ConfigurationClass}.
	 */
	private SourceClass asSourceClass(ConfigurationClass configurationClass, Predicate<String> filter) throws IOException {
		AnnotationMetadata metadata = configurationClass.getMetadata();
		if (metadata instanceof StandardAnnotationMetadata standardAnnotationMetadata) {
			return asSourceClass(standardAnnotationMetadata.getIntrospectedClass(), filter);
		}
		return asSourceClass(metadata.getClassName(), filter);
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link Class}.
	 */
	SourceClass asSourceClass(@Nullable Class<?> classType, Predicate<String> filter) throws IOException {
		if (classType == null || filter.test(classType.getName())) {
			return this.objectSourceClass;
		}
		try {
			// Sanity test that we can reflectively read annotations,
			// including Class attributes; if not -> fall back to ASM
			for (Annotation ann : classType.getDeclaredAnnotations()) {
				AnnotationUtils.validateAnnotation(ann);
			}
			return new SourceClass(classType);
		}
		catch (Throwable ex) {
			// Enforce ASM via class name resolution
			return asSourceClass(classType.getName(), filter);
		}
	}

	/**
	 * Factory method to obtain a {@link SourceClass} collection from class names.
	 */
	private Collection<SourceClass> asSourceClasses(String[] classNames, Predicate<String> filter) throws IOException {
		List<SourceClass> annotatedClasses = new ArrayList<>(classNames.length);
		for (String className : classNames) {
			SourceClass sourceClass = asSourceClass(className, filter);
			if (this.objectSourceClass != sourceClass) {
				annotatedClasses.add(sourceClass);
			}
		}
		return annotatedClasses;
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a class name.
	 */
	SourceClass asSourceClass(@Nullable String className, Predicate<String> filter) throws IOException {
		if (className == null || filter.test(className)) {
			return this.objectSourceClass;
		}
		if (className.startsWith("java")) {
			// Never use ASM for core java types
			try {
				return new SourceClass(ClassUtils.forName(className, this.resourceLoader.getClassLoader()));
			}
			catch (ClassNotFoundException ex) {
				throw new IOException("Failed to load class [" + className + "]", ex);
			}
		}
		return new SourceClass(this.metadataReaderFactory.getMetadataReader(className));
	}

	private List<Condition> collectRegisterBeanConditions(ConfigurationClass configurationClass) {
		AnnotationMetadata metadata = configurationClass.getMetadata();
		List<Condition> allConditions = new ArrayList<>(this.conditionEvaluator.collectConditions(metadata));
		ConfigurationClass enclosingConfigurationClass = getEnclosingConfigurationClass(configurationClass);
		if (enclosingConfigurationClass != null) {
			allConditions.addAll(this.conditionEvaluator.collectConditions(enclosingConfigurationClass.getMetadata()));
		}
		return allConditions.stream().filter(REGISTER_BEAN_CONDITION_FILTER).toList();
	}

	private @Nullable ConfigurationClass getEnclosingConfigurationClass(ConfigurationClass configurationClass) {
		String enclosingClassName = configurationClass.getMetadata().getEnclosingClassName();
		if (enclosingClassName != null) {
			return configurationClass.getImportedBy().stream()
					.filter(candidate -> enclosingClassName.equals(candidate.getMetadata().getClassName()))
					.findFirst().orElse(null);
		}
		return null;
	}


	@SuppressWarnings("serial")
	private class ImportStack extends ArrayDeque<ConfigurationClass> implements ImportRegistry {

		private final MultiValueMap<String, AnnotationMetadata> imports = new LinkedMultiValueMap<>();

		void registerImport(AnnotationMetadata importingClass, String importedClass) {
			this.imports.add(importedClass, importingClass);
		}

		@Override
		public @Nullable AnnotationMetadata getImportingClassFor(String importedClass) {
			return CollectionUtils.lastElement(this.imports.get(importedClass));
		}

		@Override
		public void removeImportingClass(String importingClass) {
			for (List<AnnotationMetadata> list : this.imports.values()) {
				for (Iterator<AnnotationMetadata> iterator = list.iterator(); iterator.hasNext();) {
					if (iterator.next().getClassName().equals(importingClass)) {
						iterator.remove();
						break;
					}
				}
			}
			removeKnownSuperclass(importingClass, true);
		}

		/**
		 * Given a stack containing (in order)
		 * <ul>
		 * <li>com.acme.Foo</li>
		 * <li>com.acme.Bar</li>
		 * <li>com.acme.Baz</li>
		 * </ul>
		 * return "[Foo->Bar->Baz]".
		 */
		@Override
		public String toString() {
			StringJoiner joiner = new StringJoiner("->", "[", "]");
			for (ConfigurationClass configurationClass : this) {
				joiner.add(configurationClass.getSimpleName());
			}
			return joiner.toString();
		}
	}


	private class DeferredImportSelectorHandler {

		private @Nullable List<DeferredImportSelectorHolder> deferredImportSelectors = new ArrayList<>();

		/**
		 * Handle the specified {@link DeferredImportSelector}. If deferred import
		 * selectors are being collected, this registers this instance to the list. If
		 * they are being processed, the {@link DeferredImportSelector} is also processed
		 * immediately according to its {@link DeferredImportSelector.Group}.
		 * @param configClass the source configuration class
		 * @param importSelector the selector to handle
		 */
		void handle(ConfigurationClass configClass, DeferredImportSelector importSelector) {
			DeferredImportSelectorHolder holder = new DeferredImportSelectorHolder(configClass, importSelector);
			if (this.deferredImportSelectors == null) {
				DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
				handler.register(holder);
				handler.processGroupImports();
			}
			else {
				this.deferredImportSelectors.add(holder);
			}
		}

		void process() {
			List<DeferredImportSelectorHolder> deferredImports = this.deferredImportSelectors;
			this.deferredImportSelectors = null;
			try {
				if (deferredImports != null) {
					DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
					deferredImports.sort(DEFERRED_IMPORT_COMPARATOR);
					deferredImports.forEach(handler::register);
					handler.processGroupImports();
				}
			}
			finally {
				this.deferredImportSelectors = new ArrayList<>();
			}
		}
	}


	private class DeferredImportSelectorGroupingHandler {

		private final Map<Object, DeferredImportSelectorGrouping> groupings = new LinkedHashMap<>();

		private final Map<AnnotationMetadata, ConfigurationClass> configurationClasses = new HashMap<>();

		void register(DeferredImportSelectorHolder deferredImport) {
			Class<? extends Group> group = deferredImport.getImportSelector().getImportGroup();
			DeferredImportSelectorGrouping grouping = this.groupings.computeIfAbsent(
					(group != null ? group : deferredImport),
					key -> new DeferredImportSelectorGrouping(createGroup(group)));
			grouping.add(deferredImport);
			this.configurationClasses.put(deferredImport.getConfigurationClass().getMetadata(),
					deferredImport.getConfigurationClass());
		}

		void processGroupImports() {
			for (DeferredImportSelectorGrouping grouping : this.groupings.values()) {
				Predicate<String> filter = grouping.getCandidateFilter();
				grouping.getImports().forEach(entry -> {
					ConfigurationClass configurationClass = this.configurationClasses.get(entry.getMetadata());
					Assert.state(configurationClass != null, "ConfigurationClass must not be null");
					try {
						processImports(configurationClass, asSourceClass(configurationClass, filter),
								Collections.singleton(asSourceClass(entry.getImportClassName(), filter)),
								filter, false);
					}
					catch (BeanDefinitionStoreException ex) {
						throw ex;
					}
					catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to process import candidates for configuration class [" +
										configurationClass.getMetadata().getClassName() + "]", ex);
					}
				});
			}
		}

		private Group createGroup(@Nullable Class<? extends Group> type) {
			Class<? extends Group> effectiveType = (type != null ? type : DefaultDeferredImportSelectorGroup.class);
			return ParserStrategyUtils.instantiateClass(effectiveType, Group.class,
					ConfigurationClassParser.this.environment,
					ConfigurationClassParser.this.resourceLoader,
					ConfigurationClassParser.this.registry);
		}
	}


	private static class DeferredImportSelectorHolder {

		private final ConfigurationClass configurationClass;

		private final DeferredImportSelector importSelector;

		DeferredImportSelectorHolder(ConfigurationClass configClass, DeferredImportSelector selector) {
			this.configurationClass = configClass;
			this.importSelector = selector;
		}

		ConfigurationClass getConfigurationClass() {
			return this.configurationClass;
		}

		DeferredImportSelector getImportSelector() {
			return this.importSelector;
		}
	}


	private static class DeferredImportSelectorGrouping {

		private final DeferredImportSelector.Group group;

		private final List<DeferredImportSelectorHolder> deferredImports = new ArrayList<>();

		DeferredImportSelectorGrouping(Group group) {
			this.group = group;
		}

		void add(DeferredImportSelectorHolder deferredImport) {
			this.deferredImports.add(deferredImport);
		}

		/**
		 * Return the imports defined by the group.
		 * @return each import with its associated configuration class
		 */
		Iterable<Group.Entry> getImports() {
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				this.group.process(deferredImport.getConfigurationClass().getMetadata(),
						deferredImport.getImportSelector());
			}
			return this.group.selectImports();
		}

		Predicate<String> getCandidateFilter() {
			Predicate<String> mergedFilter = DEFAULT_EXCLUSION_FILTER;
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				Predicate<String> selectorFilter = deferredImport.getImportSelector().getExclusionFilter();
				if (selectorFilter != null) {
					mergedFilter = mergedFilter.or(selectorFilter);
				}
			}
			return mergedFilter;
		}
	}


	private static class DefaultDeferredImportSelectorGroup implements Group {

		private final List<Entry> imports = new ArrayList<>();

		@Override
		public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
			for (String importClassName : selector.selectImports(metadata)) {
				this.imports.add(new Entry(metadata, importClassName));
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			return this.imports;
		}
	}


	/**
	 * Simple wrapper that allows annotated source classes to be dealt with
	 * in a uniform manner, regardless of how they are loaded.
	 */
	private class SourceClass implements Ordered {

		private final Object source;  // Class or MetadataReader

		private final AnnotationMetadata metadata;

		public SourceClass(Object source) {
			this.source = source;
			if (source instanceof Class<?> sourceClass) {
				this.metadata = AnnotationMetadata.introspect(sourceClass);
			}
			else {
				this.metadata = ((MetadataReader) source).getAnnotationMetadata();
			}
		}

		public final AnnotationMetadata getMetadata() {
			return this.metadata;
		}

		@Override
		public int getOrder() {
			Integer order = ConfigurationClassUtils.getOrder(this.metadata);
			return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
		}

		public Class<?> loadClass() throws ClassNotFoundException {
			if (this.source instanceof Class<?> sourceClass) {
				return sourceClass;
			}
			String className = ((MetadataReader) this.source).getClassMetadata().getClassName();
			return ClassUtils.forName(className, resourceLoader.getClassLoader());
		}

		public boolean isAssignable(Class<?> clazz) throws IOException {
			if (this.source instanceof Class<?> sourceClass) {
				return clazz.isAssignableFrom(sourceClass);
			}
			return new AssignableTypeFilter(clazz).match((MetadataReader) this.source, metadataReaderFactory);
		}

		public ConfigurationClass asConfigClass(ConfigurationClass importedBy) {
			if (this.source instanceof Class<?> sourceClass) {
				return new ConfigurationClass(sourceClass, importedBy);
			}
			return new ConfigurationClass((MetadataReader) this.source, importedBy);
		}

		public Collection<SourceClass> getMemberClasses() throws IOException {
			Object sourceToProcess = this.source;
			if (sourceToProcess instanceof Class<?> sourceClass) {
				try {
					Class<?>[] declaredClasses = sourceClass.getDeclaredClasses();
					List<SourceClass> members = new ArrayList<>(declaredClasses.length);
					for (Class<?> declaredClass : declaredClasses) {
						members.add(asSourceClass(declaredClass, DEFAULT_EXCLUSION_FILTER));
					}
					return members;
				}
				catch (NoClassDefFoundError err) {
					// getDeclaredClasses() failed because of non-resolvable dependencies
					// -> fall back to ASM below
					sourceToProcess = metadataReaderFactory.getMetadataReader(sourceClass.getName());
				}
			}

			// ASM-based resolution - safe for non-resolvable classes as well
			MetadataReader sourceReader = (MetadataReader) sourceToProcess;
			String[] memberClassNames = sourceReader.getClassMetadata().getMemberClassNames();
			List<SourceClass> members = new ArrayList<>(memberClassNames.length);
			for (String memberClassName : memberClassNames) {
				try {
					members.add(asSourceClass(memberClassName, DEFAULT_EXCLUSION_FILTER));
				}
				catch (IOException ex) {
					// Let's skip it if it's not resolvable - we're just looking for candidates
					if (logger.isDebugEnabled()) {
						logger.debug("Failed to resolve member class [" + memberClassName +
								"] - not considering it as a configuration class candidate");
					}
				}
			}
			return members;
		}

		public SourceClass getSuperClass() throws IOException {
			if (this.source instanceof Class<?> sourceClass) {
				return asSourceClass(sourceClass.getSuperclass(), DEFAULT_EXCLUSION_FILTER);
			}
			return asSourceClass(
					((MetadataReader) this.source).getClassMetadata().getSuperClassName(), DEFAULT_EXCLUSION_FILTER);
		}

		public Set<SourceClass> getInterfaces() throws IOException {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class<?> sourceClass) {
				for (Class<?> ifcClass : sourceClass.getInterfaces()) {
					result.add(asSourceClass(ifcClass, DEFAULT_EXCLUSION_FILTER));
				}
			}
			else {
				for (String className : this.metadata.getInterfaceNames()) {
					result.add(asSourceClass(className, DEFAULT_EXCLUSION_FILTER));
				}
			}
			return result;
		}

		public Set<SourceClass> getAnnotations() {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class<?> sourceClass) {
				for (Annotation ann : sourceClass.getDeclaredAnnotations()) {
					Class<?> annType = ann.annotationType();
					if (!annType.getName().startsWith("java")) {
						try {
							result.add(asSourceClass(annType, DEFAULT_EXCLUSION_FILTER));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			else {
				for (String className : this.metadata.getAnnotationTypes()) {
					if (!className.startsWith("java")) {
						try {
							result.add(getRelated(className));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			return result;
		}

		public Collection<SourceClass> getAnnotationAttributes(String annType, String attribute) throws IOException {
			Map<String, @Nullable Object> annotationAttributes = this.metadata.getAnnotationAttributes(annType, true);
			if (annotationAttributes == null || !annotationAttributes.containsKey(attribute)) {
				return Collections.emptySet();
			}
			String[] classNames = (String[]) annotationAttributes.get(attribute);
			Set<SourceClass> result = CollectionUtils.newLinkedHashSet(classNames.length);
			for (String className : classNames) {
				result.add(getRelated(className));
			}
			return result;
		}

		private SourceClass getRelated(String className) throws IOException {
			if (this.source instanceof Class<?> sourceClass) {
				try {
					Class<?> clazz = ClassUtils.forName(className, sourceClass.getClassLoader());
					return asSourceClass(clazz, DEFAULT_EXCLUSION_FILTER);
				}
				catch (ClassNotFoundException ex) {
					// Ignore -> fall back to ASM next, except for core java types.
					if (className.startsWith("java")) {
						throw new IOException("Failed to load class [" + className + "]", ex);
					}
					return new SourceClass(metadataReaderFactory.getMetadataReader(className));
				}
			}
			return asSourceClass(className, DEFAULT_EXCLUSION_FILTER);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return (this == other || (other instanceof SourceClass that &&
					this.metadata.getClassName().equals(that.metadata.getClassName())));
		}

		@Override
		public int hashCode() {
			return this.metadata.getClassName().hashCode();
		}

		@Override
		public String toString() {
			return this.metadata.getClassName();
		}
	}


	/**
	 * {@link Problem} registered upon detection of a circular {@link Import}.
	 */
	private static class CircularImportProblem extends Problem {

		public CircularImportProblem(ConfigurationClass attemptedImport, Deque<ConfigurationClass> importStack) {
			super(String.format("A circular @Import has been detected: " +
					"Illegal attempt by @Configuration class '%s' to import class '%s' as '%s' is " +
					"already present in the current import stack %s", importStack.element().getSimpleName(),
					attemptedImport.getSimpleName(), attemptedImport.getSimpleName(), importStack),
					new Location(importStack.element().getResource(), attemptedImport.getMetadata()));
		}
	}

}
