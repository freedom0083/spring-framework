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

package org.springframework.beans.factory.support;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Utility methods that are useful for bean definition reader implementations.
 * Mainly intended for internal use.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 1.1
 * @see PropertiesBeanDefinitionReader
 * @see org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader
 */
public abstract class BeanDefinitionReaderUtils {

	/**
	 * Separator for generated bean names. If a class name or parent name is not
	 * unique, "#1", "#2" etc will be appended, until the name becomes unique.
	 */
	public static final String GENERATED_BEAN_NAME_SEPARATOR = BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR;


	/**
	 * Create a new GenericBeanDefinition for the given parent name and class name,
	 * eagerly loading the bean class if a ClassLoader has been specified.
	 * @param parentName the name of the parent bean, if any
	 * @param className the name of the bean class, if any
	 * @param classLoader the ClassLoader to use for loading bean classes
	 * (can be {@code null} to just register bean classes by name)
	 * @return the bean definition
	 * @throws ClassNotFoundException if the bean class could not be loaded
	 */
	public static AbstractBeanDefinition createBeanDefinition(
			@Nullable String parentName, @Nullable String className, @Nullable ClassLoader classLoader) throws ClassNotFoundException {

		GenericBeanDefinition bd = new GenericBeanDefinition();
		// TODO 设置双亲bd
		bd.setParentName(parentName);
		if (className != null) {
			// TODO 设置了class属性时
			if (classLoader != null) {
				// TODO 如果设置了类加载器, 则对指定类名进行加载, 将加载后的实例放到bd的beanClass属性中
				bd.setBeanClass(ClassUtils.forName(className, classLoader));
			}
			else {
				// TODO 如果没有类加载器, 直接将字符串做为beanClass属性
				bd.setBeanClassName(className);
			}
		}
		// TODO 如果bean的配置中没有设置'class'属性, 则返回的bd的beanClass属性为null
		return bd;
	}

	/**
	 * Generate a bean name for the given top-level bean definition,
	 * unique within the given bean factory.
	 * @param beanDefinition the bean definition to generate a bean name for
	 * @param registry the bean factory that the definition is going to be
	 * registered with (to check for existing bean names)
	 * @return the generated bean name
	 * @throws BeanDefinitionStoreException if no unique name can be generated
	 * for the given bean definition
	 * @see #generateBeanName(BeanDefinition, BeanDefinitionRegistry, boolean)
	 */
	public static String generateBeanName(BeanDefinition beanDefinition, BeanDefinitionRegistry registry)
			throws BeanDefinitionStoreException {

		return generateBeanName(beanDefinition, registry, false);
	}

	/**
	 * Generate a bean name for the given bean definition, unique within the
	 * given bean factory.
	 * @param definition the bean definition to generate a bean name for
	 * @param registry the bean factory that the definition is going to be
	 * registered with (to check for existing bean names)
	 * @param isInnerBean whether the given bean definition will be registered
	 * as inner bean or as top-level bean (allowing for special name generation
	 * for inner beans versus top-level beans)
	 * @return the generated bean name
	 * @throws BeanDefinitionStoreException if no unique name can be generated
	 * for the given bean definition
	 */
	public static String generateBeanName(
			BeanDefinition definition, BeanDefinitionRegistry registry, boolean isInnerBean)
			throws BeanDefinitionStoreException {
		// TODO 通过BeanDefinition的beanClass属性来取得bean的名字. 根据前面所述, beanClass是由配置文件中'class'属性指定的class
		//  或字符串名, 这里的getBeanClassName()会根据beanClass类型的不同来取得beanName
		//  1. class: 取得class对应的name
		//  2. 字符串: 直接取得
		String generatedBeanName = definition.getBeanClassName();
		if (generatedBeanName == null) {
			// TODO 为空时表示没有设置'class'属性, 这时看一下是否有'parent'属性
			if (definition.getParentName() != null) {
				// TODO 如果配置了'parent'属性, 则命名为: 'parent'对应的名字+'$child'
				generatedBeanName = definition.getParentName() + "$child";
			}
			// TODO 在没设置'parent'属性时, 看'factory-bean'属性
			else if (definition.getFactoryBeanName() != null) {
				// TODO 如果配置了'factory-bean'属性, 则命名为: 'factory-bean'对应的名字+'$created'
				generatedBeanName = definition.getFactoryBeanName() + "$created";
			}
		}
		if (!StringUtils.hasText(generatedBeanName)) {
			// TODO 还不能生成名字, 就抛异常了
			throw new BeanDefinitionStoreException("Unnamed bean definition specifies neither " +
					"'class' nor 'parent' nor 'factory-bean' - can't generate bean name");
		}

		String id = generatedBeanName;
		if (isInnerBean) {
			// Inner bean: generate identity hashcode suffix.
			// TODO 如果是内部类, 重新命名为: 刚才生成的bean名+'#'+bd转化的16进制字符串
			id = generatedBeanName + GENERATED_BEAN_NAME_SEPARATOR + ObjectUtils.getIdentityHexString(definition);
		}
		else {
			// Top-level bean: use plain class name with unique suffix if necessary.
			// TODO 用刚才生成的bean名生成一个容器中唯一的名字, 即刚才生成的bean名+0开始的序列号
			return uniqueBeanName(generatedBeanName, registry);
		}
		return id;
	}

	/**
	 * Turn the given bean name into a unique bean name for the given bean factory,
	 * appending a unique counter as suffix if necessary.
	 * @param beanName the original bean name
	 * @param registry the bean factory that the definition is going to be
	 * registered with (to check for existing bean names)
	 * @return the unique bean name to use
	 * @since 5.1
	 */
	public static String uniqueBeanName(String beanName, BeanDefinitionRegistry registry) {
		String id = beanName;
		int counter = -1;

		// Increase counter until the id is unique.
		while (counter == -1 || registry.containsBeanDefinition(id)) {
			counter++;
			// TODO 生成一个容器中全局唯一的名字: beanName+'#'+自增的号
			id = beanName + GENERATED_BEAN_NAME_SEPARATOR + counter;
		}
		return id;
	}

	/**
	 * Register the given bean definition with the given bean factory.
	 * @param definitionHolder the bean definition including name and aliases
	 * @param registry the bean factory to register with
	 * @throws BeanDefinitionStoreException if registration failed
	 */
	public static void registerBeanDefinition(
			BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry)
			throws BeanDefinitionStoreException {

		// Register bean definition under primary name.
		String beanName = definitionHolder.getBeanName();
		// TODO 委托给BeanDefinitionRegistry进行注册
		registry.registerBeanDefinition(beanName, definitionHolder.getBeanDefinition());

		// Register aliases for bean name, if any.
		String[] aliases = definitionHolder.getAliases();
		if (aliases != null) {
			for (String alias : aliases) {
				registry.registerAlias(beanName, alias);
			}
		}
	}

	/**
	 * Register the given bean definition with a generated name,
	 * unique within the given bean factory.
	 * @param definition the bean definition to generate a bean name for
	 * @param registry the bean factory to register with
	 * @return the generated bean name
	 * @throws BeanDefinitionStoreException if no unique name can be generated
	 * for the given bean definition or the definition cannot be registered
	 */
	public static String registerWithGeneratedName(
			AbstractBeanDefinition definition, BeanDefinitionRegistry registry)
			throws BeanDefinitionStoreException {

		String generatedName = generateBeanName(definition, registry, false);
		registry.registerBeanDefinition(generatedName, definition);
		return generatedName;
	}

}
