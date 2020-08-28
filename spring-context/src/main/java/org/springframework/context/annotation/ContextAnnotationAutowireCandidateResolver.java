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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Complete implementation of the
 * {@link org.springframework.beans.factory.support.AutowireCandidateResolver} strategy
 * interface, providing support for qualifier annotations as well as for lazy resolution
 * driven by the {@link Lazy} annotation in the {@code context.annotation} package.
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
public class ContextAnnotationAutowireCandidateResolver extends QualifierAnnotationAutowireCandidateResolver {

	/**
	 * 取得一个懒加载的代理
	 * @param descriptor 依赖描述的待注入项
	 * @param beanName 待创建实例的bean
	 * @return
	 */
	@Override
	@Nullable
	public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		// TODO 依赖描述的待注入项有@Lazy注解时, 构造一个懒加载的代理对象返回; 没有则直接返回null
		return (isLazy(descriptor) ? buildLazyResolutionProxy(descriptor, beanName) : null);
	}
	// TODO 是否支持懒加载
	protected boolean isLazy(DependencyDescriptor descriptor) {
		// TODO 先遍历依赖描述的待注入项(字段, 方法参数(工厂方法, 或构造函数的参数))上的注解集合, 如果带有@Lazy, 则表示支持懒加载
		for (Annotation ann : descriptor.getAnnotations()) {
			Lazy lazy = AnnotationUtils.getAnnotation(ann, Lazy.class);
			if (lazy != null && lazy.value()) {
				return true;
			}
		}
		// TODO 能走到这里, 表示依赖描述的待注入项可能是个方法中的一个参数(构造函数, 或工厂方法), 这时先把这个方法参数(工厂方法, 或构造函数的参数)拿出来
		MethodParameter methodParam = descriptor.getMethodParameter();
		if (methodParam != null) {
			// TODO 如果是方法参数(工厂方法, 或构造函数的参数), 再把此参数对应的方法拿出来
			Method method = methodParam.getMethod();
			if (method == null || void.class == method.getReturnType()) {
				// TODO 如果是构造函数(method == null), 或这个方法是个无返回值类型时, 就要看方法参数的注解是否有@Lazy, 如果有也表示支持懒加载
				Lazy lazy = AnnotationUtils.getAnnotation(methodParam.getAnnotatedElement(), Lazy.class);
				if (lazy != null && lazy.value()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 *
	 * @param descriptor 依赖描述的待注入项
	 * @param beanName 要创建实例的bean
	 * @return
	 */
	protected Object buildLazyResolutionProxy(final DependencyDescriptor descriptor, final @Nullable String beanName) {
		BeanFactory beanFactory = getBeanFactory();
		Assert.state(beanFactory instanceof DefaultListableBeanFactory,
				"BeanFactory needs to be a DefaultListableBeanFactory");
		final DefaultListableBeanFactory dlbf = (DefaultListableBeanFactory) beanFactory;

		// TODO 创建一个用于AOP的代理目标
		TargetSource ts = new TargetSource() {
			@Override
			// TODO 目标的class是被包装的参数或字段的类型
			public Class<?> getTargetClass() {
				return descriptor.getDependencyType();
			}
			@Override
			public boolean isStatic() {
				return false;
			}
			@Override
			public Object getTarget() {
				Set<String> autowiredBeanNames = (beanName != null ? new LinkedHashSet<>(1) : null);
				// TODO 解析依赖
				Object target = dlbf.doResolveDependency(descriptor, beanName, autowiredBeanNames, null);
				if (target == null) {
					// TODO 解析失败时, 根据被包装的参数或字段的类型, 返回对应的空对象
					Class<?> type = getTargetClass();
					if (Map.class == type) {
						return Collections.emptyMap();
					}
					else if (List.class == type) {
						return Collections.emptyList();
					}
					else if (Set.class == type || Collection.class == type) {
						return Collections.emptySet();
					}
					throw new NoSuchBeanDefinitionException(descriptor.getResolvableType(),
							"Optional dependency not present for lazy injection point");
				}
				if (autowiredBeanNames != null) {
					for (String autowiredBeanName : autowiredBeanNames) {
						if (dlbf.containsBean(autowiredBeanName)) {
							dlbf.registerDependentBean(autowiredBeanName, beanName);
						}
					}
				}
				return target;
			}
			@Override
			public void releaseTarget(Object target) {
			}
		};

		// TODO 创建用于返回的代理工厂, 并设置目标源, 最后返回代理对象
		ProxyFactory pf = new ProxyFactory();
		pf.setTargetSource(ts);
		Class<?> dependencyType = descriptor.getDependencyType();
		if (dependencyType.isInterface()) {
			// TODO 如果被包装的参数或字段的类型是接口类型, 为代理也设置一下接口类型
			pf.addInterface(dependencyType);
		}
		return pf.getProxy(dlbf.getBeanClassLoader());
	}

}
