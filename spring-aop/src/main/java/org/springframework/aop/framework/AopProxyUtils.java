/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.aop.framework;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import org.springframework.aop.SpringProxy;
import org.springframework.aop.TargetClassAware;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.core.DecoratingProxy;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * Utility methods for AOP proxy factories.
 * Mainly for internal use within the AOP framework.
 *
 * <p>See {@link org.springframework.aop.support.AopUtils} for a collection of
 * generic AOP utility methods which do not depend on AOP framework internals.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.aop.support.AopUtils
 */
public abstract class AopProxyUtils {

	/**
	 * Obtain the singleton target object behind the given proxy, if any.
	 * @param candidate the (potential) proxy to check
	 * @return the singleton target object managed in a {@link SingletonTargetSource},
	 * or {@code null} in any other case (not a proxy, not an existing singleton target)
	 * @since 4.3.8
	 * @see Advised#getTargetSource()
	 * @see SingletonTargetSource#getTarget()
	 */
	// TODO 用于从单例目标源返回代理目标
	@Nullable
	public static Object getSingletonTarget(Object candidate) {
		if (candidate instanceof Advised) {
			TargetSource targetSource = ((Advised) candidate).getTargetSource();
			if (targetSource instanceof SingletonTargetSource) {
				return ((SingletonTargetSource) targetSource).getTarget();
			}
		}
		return null;
	}

	/**
	 * Determine the ultimate target class of the given bean instance, traversing
	 * not only a top-level proxy but any number of nested proxies as well &mdash;
	 * as long as possible without side effects, that is, just for singleton targets.
	 * @param candidate the instance to check (might be an AOP proxy)
	 * @return the ultimate target class (or the plain class of the given
	 * object as fallback; never {@code null})
	 * @see org.springframework.aop.TargetClassAware#getTargetClass()
	 * @see Advised#getTargetSource()
	 */
	public static Class<?> ultimateTargetClass(Object candidate) {
		Assert.notNull(candidate, "Candidate object must not be null");
		Object current = candidate;
		Class<?> result = null;
		while (current instanceof TargetClassAware) {
			// TODO 取得由TargetClassAware持有的代理目标类
			result = ((TargetClassAware) current).getTargetClass();
			// TODO 这是个递归操作, 会一直向上取当前操作的对象的单例目标源中的代理目标
			current = getSingletonTarget(current);
		}
		if (result == null) {
			// TODO 如果没有得到代理目标类, 则会进行下列判断:
			//  1. 操作的对象是由CGLIB生成的代理. 这时会取由CGLIB生成的代理的原始类
			//  2. 其他情况则是直接使用当前的操作对象的Class
			result = (AopUtils.isCglibProxy(candidate) ? candidate.getClass().getSuperclass() : candidate.getClass());
		}
		return result;
	}

	/**
	 * Determine the complete set of interfaces to proxy for the given AOP configuration.
	 * <p>This will always add the {@link Advised} interface unless the AdvisedSupport's
	 * {@link AdvisedSupport#setOpaque "opaque"} flag is on. Always adds the
	 * {@link org.springframework.aop.SpringProxy} marker interface.
	 * @param advised the proxy config
	 * @return the complete set of interfaces to proxy
	 * @see SpringProxy
	 * @see Advised
	 */
	public static Class<?>[] completeProxiedInterfaces(AdvisedSupport advised) {
		return completeProxiedInterfaces(advised, false);
	}

	/**
	 * Determine the complete set of interfaces to proxy for the given AOP configuration.
	 * <p>This will always add the {@link Advised} interface unless the AdvisedSupport's
	 * {@link AdvisedSupport#setOpaque "opaque"} flag is on. Always adds the
	 * {@link org.springframework.aop.SpringProxy} marker interface.
	 * @param advised the proxy config
	 * @param decoratingProxy whether to expose the {@link DecoratingProxy} interface
	 * @return the complete set of interfaces to proxy
	 * @since 4.3
	 * @see SpringProxy
	 * @see Advised
	 * @see DecoratingProxy
	 */
	// TODO 主要是为了把SpringProxy, Advised接口添加到代理实现的接口列表里. 如果可以暴露DecoratingProxy接口, 也会将其加入到代理实现的接口列表里
	static Class<?>[] completeProxiedInterfaces(AdvisedSupport advised, boolean decoratingProxy) {
		// TODO 取得代理实现的所有接口
		Class<?>[] specifiedInterfaces = advised.getProxiedInterfaces();
		if (specifiedInterfaces.length == 0) {
			// No user-specified interfaces: check whether target class is an interface.
			// TODO 如果代理实现的接口缓存是空的, 需要重新计算一下. 先从目标源取得代理目标类
			Class<?> targetClass = advised.getTargetClass();
			if (targetClass != null) {
				if (targetClass.isInterface()) {
					// TODO 如果代理目标类是个接口, 把他也添加到代理实现的所有接口缓存里
					advised.setInterfaces(targetClass);
				}
				else if (Proxy.isProxyClass(targetClass)) {
					// TODO 如果代理类是Proxy类型, 则把其实现的接口添加到代理实现的所有接口缓存里
					advised.setInterfaces(targetClass.getInterfaces());
				}
				specifiedInterfaces = advised.getProxiedInterfaces();
			}
		}
		// TODO 判断一下SpringProxy是否在代理实现的接口列表里
		boolean addSpringProxy = !advised.isInterfaceProxied(SpringProxy.class);
		// TODO 判断一下Advised是否在代理实现的接口列表里
		boolean addAdvised = !advised.isOpaque() && !advised.isInterfaceProxied(Advised.class);
		// TODO 如果公开DecoratingProxy接口, 判断其是否在代理实现的接口列表里
		boolean addDecoratingProxy = (decoratingProxy && !advised.isInterfaceProxied(DecoratingProxy.class));
		int nonUserIfcCount = 0;
		if (addSpringProxy) {
			nonUserIfcCount++;
		}
		if (addAdvised) {
			nonUserIfcCount++;
		}
		if (addDecoratingProxy) {
			nonUserIfcCount++;
		}
		Class<?>[] proxiedInterfaces = new Class<?>[specifiedInterfaces.length + nonUserIfcCount];
		System.arraycopy(specifiedInterfaces, 0, proxiedInterfaces, 0, specifiedInterfaces.length);
		int index = specifiedInterfaces.length;
		// TODO 如果上面三个接口没有代理实现的接口列表里, 会把他们添加进去
		if (addSpringProxy) {
			proxiedInterfaces[index] = SpringProxy.class;
			index++;
		}
		if (addAdvised) {
			proxiedInterfaces[index] = Advised.class;
			index++;
		}
		if (addDecoratingProxy) {
			proxiedInterfaces[index] = DecoratingProxy.class;
		}
		return proxiedInterfaces;
	}

	/**
	 * Extract the user-specified interfaces that the given proxy implements,
	 * i.e. all non-Advised interfaces that the proxy implements.
	 * @param proxy the proxy to analyze (usually a JDK dynamic proxy)
	 * @return all user-specified interfaces that the proxy implements,
	 * in the original order (never {@code null} or empty)
	 * @see Advised
	 */
	public static Class<?>[] proxiedUserInterfaces(Object proxy) {
		Class<?>[] proxyInterfaces = proxy.getClass().getInterfaces();
		int nonUserIfcCount = 0;
		if (proxy instanceof SpringProxy) {
			nonUserIfcCount++;
		}
		if (proxy instanceof Advised) {
			nonUserIfcCount++;
		}
		if (proxy instanceof DecoratingProxy) {
			nonUserIfcCount++;
		}
		Class<?>[] userInterfaces = Arrays.copyOf(proxyInterfaces, proxyInterfaces.length - nonUserIfcCount);
		Assert.notEmpty(userInterfaces, "JDK proxy must implement one or more interfaces");
		return userInterfaces;
	}

	/**
	 * Check equality of the proxies behind the given AdvisedSupport objects.
	 * Not the same as equality of the AdvisedSupport objects:
	 * rather, equality of interfaces, advisors and target sources.
	 */
	public static boolean equalsInProxy(AdvisedSupport a, AdvisedSupport b) {
		return (a == b ||
				(equalsProxiedInterfaces(a, b) && equalsAdvisors(a, b) && a.getTargetSource().equals(b.getTargetSource())));
	}

	/**
	 * Check equality of the proxied interfaces behind the given AdvisedSupport objects.
	 */
	public static boolean equalsProxiedInterfaces(AdvisedSupport a, AdvisedSupport b) {
		return Arrays.equals(a.getProxiedInterfaces(), b.getProxiedInterfaces());
	}

	/**
	 * Check equality of the advisors behind the given AdvisedSupport objects.
	 */
	public static boolean equalsAdvisors(AdvisedSupport a, AdvisedSupport b) {
		return Arrays.equals(a.getAdvisors(), b.getAdvisors());
	}


	/**
	 * Adapt the given arguments to the target signature in the given method,
	 * if necessary: in particular, if a given vararg argument array does not
	 * match the array type of the declared vararg parameter in the method.
	 * @param method the target method
	 * @param arguments the given arguments
	 * @return a cloned argument array, or the original if no adaptation is needed
	 * @since 4.2.3
	 */
	static Object[] adaptArgumentsIfNecessary(Method method, @Nullable Object[] arguments) {
		if (ObjectUtils.isEmpty(arguments)) {
			return new Object[0];
		}
		if (method.isVarArgs()) {
			if (method.getParameterCount() == arguments.length) {
				Class<?>[] paramTypes = method.getParameterTypes();
				int varargIndex = paramTypes.length - 1;
				Class<?> varargType = paramTypes[varargIndex];
				if (varargType.isArray()) {
					Object varargArray = arguments[varargIndex];
					if (varargArray instanceof Object[] && !varargType.isInstance(varargArray)) {
						Object[] newArguments = new Object[arguments.length];
						System.arraycopy(arguments, 0, newArguments, 0, varargIndex);
						Class<?> targetElementType = varargType.getComponentType();
						int varargLength = Array.getLength(varargArray);
						Object newVarargArray = Array.newInstance(targetElementType, varargLength);
						System.arraycopy(varargArray, 0, newVarargArray, 0, varargLength);
						newArguments[varargIndex] = newVarargArray;
						return newArguments;
					}
				}
			}
		}
		return arguments;
	}

}
