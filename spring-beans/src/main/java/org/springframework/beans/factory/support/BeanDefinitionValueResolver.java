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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Helper class for use in bean factory implementations,
 * resolving values contained in bean definition objects
 * into the actual values applied to the target bean instance.
 *
 * <p>Operates on an {@link AbstractBeanFactory} and a plain
 * {@link org.springframework.beans.factory.config.BeanDefinition} object.
 * Used by {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 1.2
 * @see AbstractAutowireCapableBeanFactory
 */
public class BeanDefinitionValueResolver {

	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final String beanName;

	private final BeanDefinition beanDefinition;

	private final TypeConverter typeConverter;


	/**
	 * Create a BeanDefinitionValueResolver for the given BeanFactory and BeanDefinition,
	 * using the given {@link TypeConverter}.
	 * @param beanFactory the BeanFactory to resolve against 解析时要用到的容器
	 * @param beanName the name of the bean that we work on 要解析的bean
	 * @param beanDefinition the BeanDefinition of the bean that we work on 要解析的bean的mbd
	 * @param typeConverter the TypeConverter to use for resolving TypedStringValues 类型转换器
	 */
	public BeanDefinitionValueResolver(AbstractAutowireCapableBeanFactory beanFactory, String beanName,
			BeanDefinition beanDefinition, TypeConverter typeConverter) {

		this.beanFactory = beanFactory;
		this.beanName = beanName;
		this.beanDefinition = beanDefinition;
		this.typeConverter = typeConverter;
	}

	/**
	 * Create a BeanDefinitionValueResolver for the given BeanFactory and BeanDefinition
	 * using a default {@link TypeConverter}.
	 * @param beanFactory the BeanFactory to resolve against
	 * @param beanName the name of the bean that we work on
	 * @param beanDefinition the BeanDefinition of the bean that we work on
	 */
	public BeanDefinitionValueResolver(AbstractAutowireCapableBeanFactory beanFactory, String beanName,
			BeanDefinition beanDefinition) {

		this.beanFactory = beanFactory;
		this.beanName = beanName;
		this.beanDefinition = beanDefinition;
		BeanWrapper beanWrapper = new BeanWrapperImpl();
		beanFactory.initBeanWrapper(beanWrapper);
		this.typeConverter = beanWrapper;
	}


	/**
	 * Given a PropertyValue, return a value, resolving any references to other
	 * beans in the factory if necessary. The value could be:
	 * <li>A BeanDefinition, which leads to the creation of a corresponding
	 * new bean instance. Singleton flags and names of such "inner beans"
	 * are always ignored: Inner beans are anonymous prototypes.
	 * <li>A RuntimeBeanReference, which must be resolved.
	 * <li>A ManagedList. This is a special collection that may contain
	 * RuntimeBeanReferences or Collections that will need to be resolved.
	 * <li>A ManagedSet. May also contain RuntimeBeanReferences or
	 * Collections that will need to be resolved.
	 * <li>A ManagedMap. In this case the value may be a RuntimeBeanReference
	 * or Collection that will need to be resolved.
	 * <li>An ordinary object or {@code null}, in which case it's left alone.
	 * @param argName the name of the argument that the value is defined for 参数名
	 * @param value the value object to resolve 是解析的propertyValue的值
	 * @return the resolved object
	 */
	public @Nullable Object resolveValueIfNecessary(Object argName, @Nullable Object value) {
		// We must check each value to see whether it requires a runtime reference
		// to another bean to be resolved.
		if (value instanceof RuntimeBeanReference ref) {
			// TODO RuntimeBeanReference用于在运行时获取BeanDefinition. 因为在我们创建这个BeanDefinition的时候我们只知道他的beanName,
			//  并不确定是否已经注册了, 这个时候就需要用RuntimeBeanReference.
			//  RuntimeBeanReference可以理解为一个引用, 需要在解析时对其进行实例化?? XML配置的形式是:
			//      <bean class="foo.bar.xxx">
			//          <property name="referBeanName" ref="otherBeanName" />
			//      </bean>
			//  其中foo.bar.xxx被解析为一个xxxBeanDefinition, 转化为代码后:
			//      reference = new RuntimeBeanReference("otherBeanName");
			//      xxxBeanDefinition.getPropertyValues().addPropertyValue("referBeanName", reference);
			//  解析ref引用对象(RuntimeBeanReference类型的propertyValues)
			return resolveReference(argName, ref);
		}
		else if (value instanceof RuntimeBeanNameReference ref) {
			// TODO 当propertyValues是RuntimeBeanNameReference, 直接解析其引用的bean名
			String refName = ref.getBeanName();
			refName = String.valueOf(doEvaluate(refName));
			if (!this.beanFactory.containsBean(refName)) {
				throw new BeanDefinitionStoreException(
						"Invalid bean name '" + refName + "' in bean reference for " + argName);
			}
			return refName;
		}
		else if (value instanceof BeanDefinitionHolder bdHolder) {
			// TODO propertyValues是BeanDefinitionHolder时, 按内嵌bean进行解析
			// Resolve BeanDefinitionHolder: contains BeanDefinition with name and aliases.
			return resolveInnerBean(bdHolder.getBeanName(), bdHolder.getBeanDefinition(),
					(name, mbd) -> resolveInnerBeanValue(argName, name, mbd));
		}
		else if (value instanceof BeanDefinition bd) {
			// TODO propertyValues是BeanDefinition时, 按内嵌bean进行解析
			return resolveInnerBean(null, bd,
					(name, mbd) -> resolveInnerBeanValue(argName, name, mbd));
		}
		else if (value instanceof DependencyDescriptor dependencyDescriptor) {
			Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
			// TODO propertyValues是一个要注入的项时, 解析其依赖关系, 找到注入候选bean
			Object result = this.beanFactory.resolveDependency(
					dependencyDescriptor, this.beanName, autowiredBeanNames, this.typeConverter);
			for (String autowiredBeanName : autowiredBeanNames) {
				if (this.beanFactory.containsBean(autowiredBeanName)) {
					// TODO 注册一下bean与注入候选项的依赖关系
					this.beanFactory.registerDependentBean(autowiredBeanName, this.beanName);
				}
			}
			return result;
		}
		else if (value instanceof ManagedArray managedArray) {
			// May need to resolve contained runtime references.
			// TODO 解析ManagedArray
			Class<?> elementType = managedArray.resolvedElementType;
			if (elementType == null) {
				String elementTypeName = managedArray.getElementTypeName();
				if (StringUtils.hasText(elementTypeName)) {
					try {
						// TODO 根据元素类型名解析元素类型
						elementType = ClassUtils.forName(elementTypeName, this.beanFactory.getBeanClassLoader());
						managedArray.resolvedElementType = elementType;
					}
					catch (Throwable ex) {
						// Improve the message by showing the context.
						throw new BeanCreationException(
								this.beanDefinition.getResourceDescription(), this.beanName,
								"Error resolving array type for " + argName, ex);
					}
				}
				else {
					// TODO 没有名字的就当做Object
					elementType = Object.class;
				}
			}
			// TODO 解析数组
			return resolveManagedArray(argName, (List<?>) value, elementType);
		}
		else if (value instanceof ManagedList<?> managedList) {
			// May need to resolve contained runtime references.
			return resolveManagedList(argName, managedList);
		}
		else if (value instanceof ManagedSet<?> managedSet) {
			// May need to resolve contained runtime references.
			return resolveManagedSet(argName, managedSet);
		}
		else if (value instanceof ManagedMap<?, ?> managedMap) {
			// May need to resolve contained runtime references.
			return resolveManagedMap(argName, managedMap);
		}
		else if (value instanceof ManagedProperties original) {
			// Properties original = managedProperties;
			Properties copy = new Properties();
			// TODO 解析Properties, key -> value形式
			original.forEach((propKey, propValue) -> {
				if (propKey instanceof TypedStringValue typedStringValue) {
					propKey = evaluate(typedStringValue);
				}
				if (propValue instanceof TypedStringValue typedStringValue) {
					propValue = evaluate(typedStringValue);
				}
				if (propKey == null || propValue == null) {
					throw new BeanCreationException(
							this.beanDefinition.getResourceDescription(), this.beanName,
							"Error converting Properties key/value pair for " + argName + ": resolved to null");
				}
				copy.put(propKey, propValue);
			});
			return copy;
		}
		else if (value instanceof TypedStringValue typedStringValue) {
			// Convert value to target type here.
			Object valueObject = evaluate(typedStringValue);
			try {
				// TODO 解析由TypedStringValue指定的代理目标类型. 如果解析出了代理目标的类型, 在进行必要的转换后返回, 否则返回value
				//  指定表达式的值
				Class<?> resolvedTargetType = resolveTargetType(typedStringValue);
				if (resolvedTargetType != null) {
					return this.typeConverter.convertIfNecessary(valueObject, resolvedTargetType);
				}
				else {
					return valueObject;
				}
			}
			catch (Throwable ex) {
				// Improve the message by showing the context.
				throw new BeanCreationException(
						this.beanDefinition.getResourceDescription(), this.beanName,
						"Error converting typed String value for " + argName, ex);
			}
		}
		else if (value instanceof NullBean) {
			return null;
		}
		else {
			// TODO 以上情况全不是时, 可能是个表达式, 解析后返回
			return evaluate(value);
		}
	}

	/**
	 * Resolve an inner bean definition and invoke the specified {@code resolver}
	 * on its merged bean definition.
	 * @param innerBeanName the inner bean name (or {@code null} to assign one)
	 * @param innerBd the inner raw bean definition
	 * @param resolver the function to invoke to resolve
	 * @param <T> the type of the resolution
	 * @return a resolved inner bean, as a result of applying the {@code resolver}
	 * @since 6.0
	 */
	public <T> T resolveInnerBean(@Nullable String innerBeanName, BeanDefinition innerBd,
			BiFunction<String, RootBeanDefinition, T> resolver) {

		String nameToUse = (innerBeanName != null ? innerBeanName : "(inner bean)" +
				BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR + ObjectUtils.getIdentityHexString(innerBd));
		return resolver.apply(nameToUse,
				this.beanFactory.getMergedBeanDefinition(nameToUse, innerBd, this.beanDefinition));
	}

	/**
	 * Evaluate the given value as an expression, if necessary.
	 * @param value the candidate value (may be an expression)
	 * @return the resolved value
	 */
	protected @Nullable Object evaluate(TypedStringValue value) {
		Object result = doEvaluate(value.getValue());
		if (!ObjectUtils.nullSafeEquals(result, value.getValue())) {
			value.setDynamic();
		}
		return result;
	}

	/**
	 * Evaluate the given value as an expression, if necessary.
	 * @param value the original value (may be an expression)
	 * @return the resolved value if necessary, or the original value
	 */
	protected @Nullable Object evaluate(@Nullable Object value) {
		if (value instanceof String str) {
			return doEvaluate(str);
		}
		else if (value instanceof String[] values) {
			boolean actuallyResolved = false;
			@Nullable Object[] resolvedValues = new Object[values.length];
			for (int i = 0; i < values.length; i++) {
				String originalValue = values[i];
				Object resolvedValue = doEvaluate(originalValue);
				if (resolvedValue != originalValue) {
					actuallyResolved = true;
				}
				resolvedValues[i] = resolvedValue;
			}
			return (actuallyResolved ? resolvedValues : values);
		}
		else {
			return value;
		}
	}

	/**
	 * Evaluate the given String value as an expression, if necessary.
	 * @param value the original value (may be an expression)
	 * @return the resolved value if necessary, or the original String value
	 */
	private @Nullable Object doEvaluate(@Nullable String value) {
		return this.beanFactory.evaluateBeanDefinitionString(value, this.beanDefinition);
	}

	/**
	 * Resolve the target type in the given TypedStringValue.
	 * @param value the TypedStringValue to resolve
	 * @return the resolved target type (or {@code null} if none specified)
	 * @throws ClassNotFoundException if the specified type cannot be resolved
	 * @see TypedStringValue#resolveTargetType
	 */
	protected @Nullable Class<?> resolveTargetType(TypedStringValue value) throws ClassNotFoundException {
		if (value.hasTargetType()) {
			return value.getTargetType();
		}
		return value.resolveTargetType(this.beanFactory.getBeanClassLoader());
	}

	/**
	 * Resolve a reference to another bean in the factory.
	 */
	// TODO 解析bean中的引用对象
	private @Nullable Object resolveReference(Object argName, RuntimeBeanReference ref) {
		try {
			Object bean;
			// TODO 取得引用的bean的类型
			Class<?> beanType = ref.getBeanType();
			// TODO 没指定类型时, 根据引用设置的名字在当前容器中查找
			String resolvedName = String.valueOf(doEvaluate(ref.getBeanName()));
			if (ref.isToParent()) {
				// TODO 如果这个引用是父容器中的显示引用, 那从父容器中去取
				BeanFactory parent = this.beanFactory.getParentBeanFactory();
				if (parent == null) {
					throw new BeanCreationException(
							this.beanDefinition.getResourceDescription(), this.beanName,
							"Cannot resolve reference to bean " + ref +
									" in parent factory: no parent factory available");
				}
				if (beanType != null) {
					bean = (parent.containsBean(resolvedName) ?
							parent.getBean(resolvedName, beanType) : parent.getBean(beanType));
				}
				else {
					// TODO 引用无法取得类型时, 就要按引用的bean的名字去取, 因为有可能是个SpEL表达式, 所以需要对名字进行解析
					bean = parent.getBean(resolvedName);
				}
			}
			else {
				if (beanType != null) {
					if (this.beanFactory.containsBean(resolvedName)) {
						bean = this.beanFactory.getBean(resolvedName, beanType);
					}
					else {
						// TODO 指定了类型时, 在当前容器中找出指定类型所对应的bean实例, bean名
						NamedBeanHolder<?> namedBean = this.beanFactory.resolveNamedBean(beanType);
						bean = namedBean.getBeanInstance();
						resolvedName = namedBean.getBeanName();
					}
				}
				else {
					bean = this.beanFactory.getBean(resolvedName);
				}
				// TODO 然后注册到容器中
				this.beanFactory.registerDependentBean(resolvedName, this.beanName);
			}
			if (bean instanceof NullBean) {
				bean = null;
			}
			// TODO 返回找到的bean, 如果是NullBean, 则返回null
			return bean;
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					this.beanDefinition.getResourceDescription(), this.beanName,
					"Cannot resolve reference to bean '" + ref.getBeanName() + "' while setting " + argName, ex);
		}
	}

	/**
	 * Resolve an inner bean definition.
	 * @param argName the name of the argument that the inner bean is defined for
	 * @param innerBeanName the name of the inner bean
	 * @param mbd the merged bean definition for the inner bean
	 * @return the resolved inner bean instance
	 */
	// TODO 解析嵌套bean
	//     <bean id="test" class="xxx.Test">
	//         <property name="innerTest">
	//             <bean id="innerTestId" class="xxx.TestId"></bean>
	//         </property>
	//     </bean>
	private @Nullable Object resolveInnerBeanValue(Object argName, String innerBeanName, RootBeanDefinition mbd) {
		try {
			// Check given bean name whether it is unique. If not already unique,
			// add counter - increasing the counter until the name is unique.
			String actualInnerBeanName = innerBeanName;
			if (mbd.isSingleton()) {
				// TODO 如果是内嵌bean是单例的, 重新确定一下内嵌bean的名字. 如果有多个同名内嵌类, 则用增量表示:
				//  内嵌bean名 + '#' + 增量
				actualInnerBeanName = adaptInnerBeanName(innerBeanName);
			}
			// TODO 把内嵌bean注册到嵌套缓存中(key -> 外层bean名, value -> 内嵌bean名), 同时注册一下他们的相互依赖关系
			this.beanFactory.registerContainedBean(actualInnerBeanName, this.beanName);
			// Guarantee initialization of beans that the inner bean depends on.
			String[] dependsOn = mbd.getDependsOn();
			if (dependsOn != null) {
				for (String dependsOnBean : dependsOn) {
					// TODO 注册并实例化内嵌bean自动所依赖的bean
					this.beanFactory.registerDependentBean(dependsOnBean, actualInnerBeanName);
					this.beanFactory.getBean(dependsOnBean);
				}
			}
			// Actually create the inner bean instance now...
			// TODO 依赖bean都创建好后, 开始创建内嵌bean
			Object innerBean = this.beanFactory.createBean(actualInnerBeanName, mbd, null);
			if (innerBean instanceof FactoryBean<?> factoryBean) {
				// TODO 如果bean实例是工厂类, 取得其中的内嵌类实例
				boolean synthetic = mbd.isSynthetic();
				innerBean = this.beanFactory.getObjectFromFactoryBean(
						factoryBean, null, actualInnerBeanName, !synthetic);
			}
			if (innerBean instanceof NullBean) {
				innerBean = null;
			}
			// TODO 返回创建好的内嵌bean
			return innerBean;
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					this.beanDefinition.getResourceDescription(), this.beanName,
					"Cannot create inner bean '" + innerBeanName + "' " +
					(mbd.getBeanClassName() != null ? "of type [" + mbd.getBeanClassName() + "] " : "") +
					"while setting " + argName, ex);
		}
	}

	/**
	 * Checks the given bean name whether it is unique. If not already unique,
	 * a counter is added, increasing the counter until the name is unique.
	 * @param innerBeanName the original name for the inner bean
	 * @return the adapted name for the inner bean
	 */
	// TODO 在内嵌bean的名字不唯一时, 创建一个增量的唯一名字
	private String adaptInnerBeanName(String innerBeanName) {
		String actualInnerBeanName = innerBeanName;
		int counter = 0;
		String prefix = innerBeanName + BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR;
		while (this.beanFactory.isBeanNameInUse(actualInnerBeanName)) {
			counter++;
			// TODO 容器中有相同的内嵌bean时, 重新命名. 格式: 内嵌bean名 + '#' + 增量值
			actualInnerBeanName = prefix + counter;
		}
		return actualInnerBeanName;
	}

	/**
	 * For each element in the managed array, resolve reference if necessary.
	 */
	private Object resolveManagedArray(Object argName, List<?> ml, Class<?> elementType) {
		Object resolved = Array.newInstance(elementType, ml.size());
		for (int i = 0; i < ml.size(); i++) {
			// TODO 解析数组中的每一个元素
			Array.set(resolved, i, resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
		}
		return resolved;
	}

	/**
	 * For each element in the managed list, resolve reference if necessary.
	 */
	private List<?> resolveManagedList(Object argName, List<?> ml) {
		List<Object> resolved = new ArrayList<>(ml.size());
		for (int i = 0; i < ml.size(); i++) {
			resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
		}
		return resolved;
	}

	/**
	 * For each element in the managed set, resolve reference if necessary.
	 */
	private Set<?> resolveManagedSet(Object argName, Set<?> ms) {
		Set<Object> resolved = CollectionUtils.newLinkedHashSet(ms.size());
		int i = 0;
		for (Object m : ms) {
			resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), m));
			i++;
		}
		return resolved;
	}

	/**
	 * For each element in the managed map, resolve reference if necessary.
	 */
	private Map<?, ?> resolveManagedMap(Object argName, Map<?, ?> mm) {
		Map<Object, Object> resolved = CollectionUtils.newLinkedHashMap(mm.size());
		mm.forEach((key, value) -> {
			Object resolvedKey = resolveValueIfNecessary(argName, key);
			Object resolvedValue = resolveValueIfNecessary(new KeyedArgName(argName, key), value);
			resolved.put(resolvedKey, resolvedValue);
		});
		return resolved;
	}


	/**
	 * Holder class used for delayed toString building.
	 */
	private static class KeyedArgName {

		private final Object argName;

		private final Object key;

		public KeyedArgName(Object argName, Object key) {
			this.argName = argName;
			this.key = key;
		}

		@Override
		public String toString() {
			return this.argName + " with key " + BeanWrapper.PROPERTY_KEY_PREFIX +
					this.key + BeanWrapper.PROPERTY_KEY_SUFFIX;
		}
	}

}
