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

package org.springframework.aop;

import org.jspecify.annotations.Nullable;

/**
 * A {@code TargetSource} is used to obtain the current "target" of
 * an AOP invocation, which will be invoked via reflection if no around
 * advice chooses to end the interceptor chain itself.
 *
 * <p>If a {@code TargetSource} is "static", it will always return
 * the same target, allowing optimizations in the AOP framework. Dynamic
 * target sources can support pooling, hot swapping, etc.
 *
 * <p>Application developers don't usually need to work with
 * {@code TargetSources} directly: this is an AOP framework interface.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 */
// TODO 此接口表示的是代理目标的来源, 其持有代理目标Target.
public interface TargetSource extends TargetClassAware {

	/**
	 * Return the type of targets returned by this {@link TargetSource}.
	 * <p>Can return {@code null}, although certain usages of a {@code TargetSource}
	 * might just work with a predetermined target class.
	 * @return the type of targets returned by this {@link TargetSource}
	 */
	// TODO 获取代理目标对象的类型
	@Override
	@Nullable Class<?> getTargetClass();

	/**
	 * Will all calls to {@link #getTarget()} return the same object?
	 * <p>In that case, there will be no need to invoke {@link #releaseTarget(Object)},
	 * and the AOP framework can cache the return value of {@link #getTarget()}.
	 * <p>The default implementation returns {@code false}.
	 * @return {@code true} if the target is immutable
	 * @see #getTarget
	 */
	// TODO 标识代理目标是否为静态. 对于静态代理目标来说, Spring AOP会在getTarget()后对其进行缓存, 这样每次返回的就都是相同的代理目标.
	//  对于非静态代理目标来说, 因为需要每次重新创建实例, 所以会在执行结束后调用releaseTarget()来释放代理目标
	default boolean isStatic() {
		return false;
	}

	/**
	 * Return a target instance. Invoked immediately before the
	 * AOP framework calls the "target" of an AOP method invocation.
	 * @return the target object which contains the joinpoint,
	 * or {@code null} if there is no actual target instance
	 * @throws Exception if the target object can't be resolved
	 */
	// TODO 取得目标实例. 在每次MethodInvocation方法调用执行之前会获取
	@Nullable Object getTarget() throws Exception;

	/**
	 * Release the given target object obtained from the
	 * {@link #getTarget()} method, if any.
	 * <p>The default implementation is empty.
	 * @param target object obtained from a call to {@link #getTarget()}
	 * @throws Exception if the object can't be released
	 */
	// TODO 对于非静态的代理目标来说, 会释放getTarget()取得的代理目标对象
	default void releaseTarget(Object target) throws Exception {
	}

}
