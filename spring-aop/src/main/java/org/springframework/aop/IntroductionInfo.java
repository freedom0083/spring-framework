/*
 * Copyright 2002-2012 the original author or authors.
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

/**
 * Interface supplying the information necessary to describe an introduction.
 *
 * <p>{@link IntroductionAdvisor IntroductionAdvisors} must implement this
 * interface. If an {@link org.aopalliance.aop.Advice} implements this,
 * it may be used as an introduction without an {@link IntroductionAdvisor}.
 * In this case, the advice is self-describing, providing not only the
 * necessary behavior, but describing the interfaces it introduces.
 *
 * @author Rod Johnson
 * @since 1.1.1
 */
// TODO Introduction(可以叫引入么??)可以在不改变目标类的情况下, 为其增加新的属性和行为. 而此接口就是用来描述目标类需要实现的新接口.
//  这里提到了'org.aopalliance.aop.Advice'直接实现这个接口的情况. 因为IntroductionAdvisor接口本身必须实现此接口, 所以如果某个Advice
//  直接实现了此接口, 则这个Advice就可以直接当一个Introduction来使用, 而不用再实现IntroductionAdvisor接口了. 而此时, 这个Advice
//  就是一个自描述的了, 其不止提供自身必要的行为, 还描述了其引入的新接口
public interface IntroductionInfo {

	/**
	 * Return the additional interfaces introduced by this Advisor or Advice.
	 * @return the introduced interfaces
	 */
	// TODO 这里得到的是为目标类引入的, 需要实现的新接口
	Class<?>[] getInterfaces();

}
