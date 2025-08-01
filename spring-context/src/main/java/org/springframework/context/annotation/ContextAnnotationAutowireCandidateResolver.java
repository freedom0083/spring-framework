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

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;

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
	public @Nullable Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		// TODO 依赖描述的待注入项有@Lazy注解时, 构造一个懒加载的代理对象返回; 没有则直接返回null
		return (isLazy(descriptor) ? buildLazyResolutionProxy(descriptor, beanName) : null);
	}

	@Override
	// TODO 是否支持懒加载
	public @Nullable Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, @Nullable String beanName) {
		return (isLazy(descriptor) ? (Class<?>) buildLazyResolutionProxy(descriptor, beanName, true) : null);
	}

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
	protected Object buildLazyResolutionProxy(DependencyDescriptor descriptor, @Nullable String beanName) {
		return buildLazyResolutionProxy(descriptor, beanName, false);
	}

	private Object buildLazyResolutionProxy(
			DependencyDescriptor descriptor, @Nullable String beanName, boolean classOnly) {

		if (!(getBeanFactory() instanceof DefaultListableBeanFactory dlbf)) {
			throw new IllegalStateException("Lazy resolution only supported with DefaultListableBeanFactory");
		}

		// TODO 创建一个用于AOP的代理目标
		TargetSource ts = new LazyDependencyTargetSource(dlbf, descriptor, beanName);

		ProxyFactory pf = new ProxyFactory();
		pf.setTargetSource(ts);
		Class<?> dependencyType = descriptor.getDependencyType();
		if (dependencyType.isInterface()) {
			pf.addInterface(dependencyType);
		}
		ClassLoader classLoader = dlbf.getBeanClassLoader();
		return (classOnly ? pf.getProxyClass(classLoader) : pf.getProxy(classLoader));
	}


	@SuppressWarnings("serial")
	private static class LazyDependencyTargetSource implements TargetSource, Serializable {

		private final DefaultListableBeanFactory beanFactory;

		private final DependencyDescriptor descriptor;

		private final @Nullable String beanName;

		private transient volatile @Nullable Object cachedTarget;

		public LazyDependencyTargetSource(DefaultListableBeanFactory beanFactory,
				DependencyDescriptor descriptor, @Nullable String beanName) {

			this.beanFactory = beanFactory;
			this.descriptor = descriptor;
			this.beanName = beanName;
		}

		@Override
		// TODO 目标的class是被包装的参数或字段的类型
		public Class<?> getTargetClass() {
			return this.descriptor.getDependencyType();
		}

		@Override
		public Object getTarget() {
			Object cachedTarget = this.cachedTarget;
			if (cachedTarget != null) {
				return cachedTarget;
			}

			Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
			// TODO 解析依赖
			Object target = this.beanFactory.doResolveDependency(
					this.descriptor, this.beanName, autowiredBeanNames, null);

			if (target == null) {
				// TODO 解析失败时, 根据被包装的参数或字段的类型, 返回对应的空对象
				Class<?> type = getTargetClass();
				if (Map.class == type) {
					target = Collections.emptyMap();
				}
				else if (List.class == type) {
					target = Collections.emptyList();
				}
				else if (Set.class == type || Collection.class == type) {
					target = Collections.emptySet();
				}
				else {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType(),
							"Optional dependency not present for lazy injection point");
				}
			}
			else {
				if (target instanceof Map<?, ?> map && Map.class == getTargetClass()) {
					target = Collections.unmodifiableMap(map);
				}
				else if (target instanceof List<?> list && List.class == getTargetClass()) {
					target = Collections.unmodifiableList(list);
				}
				else if (target instanceof Set<?> set && Set.class == getTargetClass()) {
					target = Collections.unmodifiableSet(set);
				}
				else if (target instanceof Collection<?> coll && Collection.class == getTargetClass()) {
					target = Collections.unmodifiableCollection(coll);
				}
			}

			boolean cacheable = true;
			for (String autowiredBeanName : autowiredBeanNames) {
				if (!this.beanFactory.containsBean(autowiredBeanName)) {
					cacheable = false;
				}
				else {
					if (this.beanName != null) {
						this.beanFactory.registerDependentBean(autowiredBeanName, this.beanName);
					}
					if (!this.beanFactory.isSingleton(autowiredBeanName)) {
						cacheable = false;
					}
				}
				if (cacheable) {
					this.cachedTarget = target;
				}
			}

			return target;
		}
	}

}
