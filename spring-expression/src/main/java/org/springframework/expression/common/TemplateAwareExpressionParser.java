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

package org.springframework.expression.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.ParserContext;
import org.springframework.lang.Nullable;

/**
 * An expression parser that understands templates. It can be subclassed by expression
 * parsers that do not offer first class support for templating.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @author Andy Clement
 * @since 3.0
 */
public abstract class TemplateAwareExpressionParser implements ExpressionParser {

	@Override
	public Expression parseExpression(String expressionString) throws ParseException {
		return parseExpression(expressionString, null);
	}

	@Override
	public Expression parseExpression(String expressionString, @Nullable ParserContext context) throws ParseException {
		if (context != null && context.isTemplate()) {
			// TODO 通过模版解析表达式
			return parseTemplate(expressionString, context);
		}
		else {
			// TODO 直接解析表达式, 直接解析时, 并没有使用解析上下文context
			return doParseExpression(expressionString, context);
		}
	}


	private Expression parseTemplate(String expressionString, ParserContext context) throws ParseException {
		if (expressionString.isEmpty()) {
			// TODO 要解析的内容是空时, 返回一个表示空字符串的字面量expression
			return new LiteralExpression("");
		}

		Expression[] expressions = parseExpressions(expressionString, context);
		if (expressions.length == 1) {
			// TODO 如果解析出来是一个expression, 则返回头一个
			return expressions[0];
		}
		else {
			// TODO 如果是多个表达式, 使用CompositeStringExpression进行封装并返回.
			//  CompositeStringExpression是一个聚合, 表示一个分为多个部分的模板表达式(只处理Template模式), 其每个部分都是表达式,
			//  但模板的纯文本部分将表示为LiteralExpression对象
			return new CompositeStringExpression(expressionString, expressions);
		}
	}

	/**
	 * Helper that parses given expression string using the configured parser. The
	 * expression string can contain any number of expressions all contained in "${...}"
	 * markers. For instance: "foo${expr0}bar${expr1}". The static pieces of text will
	 * also be returned as Expressions that just return that static piece of text. As a
	 * result, evaluating all returned expressions and concatenating the results produces
	 * the complete evaluated string. Unwrapping is only done of the outermost delimiters
	 * found, so the string 'hello ${foo${abc}}' would break into the pieces 'hello ' and
	 * 'foo${abc}'. This means that expression languages that used ${..} as part of their
	 * functionality are supported without any problem. The parsing is aware of the
	 * structure of an embedded expression. It assumes that parentheses '(', square
	 * brackets '[' and curly brackets '}' must be in pairs within the expression unless
	 * they are within a string literal and a string literal starts and terminates with a
	 * single quote '.
	 * @param expressionString the expression string
	 * @return the parsed expressions
	 * @throws ParseException when the expressions cannot be parsed
	 */
	private Expression[] parseExpressions(String expressionString, ParserContext context) throws ParseException {
		List<Expression> expressions = new ArrayList<>();
		// TODO 解析出表示模板起始的前缀(默认是"#{"), 以及结束的后缀(默认是"}")
		String prefix = context.getExpressionPrefix();
		String suffix = context.getExpressionSuffix();
		int startIdx = 0;

		while (startIdx < expressionString.length()) {
			// TODO 找到前缀的起始位置
			int prefixIndex = expressionString.indexOf(prefix, startIdx);
			if (prefixIndex >= startIdx) {
				// an inner expression was found - this is a composite
				if (prefixIndex > startIdx) {
					// TODO 前缀的起始位置与待解析表达式部分的起始位置不同时, 表示其位于表达式中间的位置,
					//  所以把表达式起始位置到前缀起始位置间的字符串解析为一个字面量表达式, 放到表达式集合中
					expressions.add(new LiteralExpression(expressionString.substring(startIdx, prefixIndex)));
				}
				// TODO 得到前缀后的索引位置
				int afterPrefixIndex = prefixIndex + prefix.length();
				// TODO 取得后缀的索引位置, 然后对索引位置进行检查, 不能是'-1', 也不能在前缀索引范围内
				int suffixIndex = skipToCorrectEndSuffix(suffix, expressionString, afterPrefixIndex);
				if (suffixIndex == -1) {
					throw new ParseException(expressionString, prefixIndex,
							"No ending suffix '" + suffix + "' for expression starting at character " +
							prefixIndex + ": " + expressionString.substring(prefixIndex));
				}
				if (suffixIndex == afterPrefixIndex) {
					throw new ParseException(expressionString, prefixIndex,
							"No expression defined within delimiter '" + prefix + suffix +
							"' at character " + prefixIndex);
				}
				// TODO 拿出模版中间的表达式内容, 去掉空格. 其内容也不可以为空
				String expr = expressionString.substring(prefixIndex + prefix.length(), suffixIndex);
				expr = expr.trim();
				if (expr.isEmpty()) {
					throw new ParseException(expressionString, prefixIndex,
							"No expression defined within delimiter '" + prefix + suffix +
							"' at character " + prefixIndex);
				}
				// TODO 对表达式进行解析, 将解析结果入到表达式集合中
				expressions.add(doParseExpression(expr, context));
				// TODO 解析完毕后, 移动起始位置, 准备解析下一个可能存在的模版
				startIdx = suffixIndex + suffix.length();
			}
			else {
				// no more ${expressions} found in string, add rest as static text
				// TODO 待解析的字符串中没有找到前缀时, 直接生成一个表示字面量的expression入入表达式集合中
				expressions.add(new LiteralExpression(expressionString.substring(startIdx)));
				// TODO 移动起始位置, 准备解析下一个可能存在的模版
				startIdx = expressionString.length();
			}
		}

		return expressions.toArray(new Expression[0]);
	}

	/**
	 * Return true if the specified suffix can be found at the supplied position in the
	 * supplied expression string.
	 * @param expressionString the expression string which may contain the suffix 待处理的表达式,可能会包含后缀
	 * @param pos the start position at which to check for the suffix 前缀后面的索引位置, 即, 表达式的起始位置
	 * @param suffix the suffix string 后缀
	 */
	private boolean isSuffixHere(String expressionString, int pos, String suffix) {
		int suffixPosition = 0;
		for (int i = 0; i < suffix.length() && pos < expressionString.length(); i++) {
			if (expressionString.charAt(pos++) != suffix.charAt(suffixPosition++)) {
				// TODO 表达式中不包含后缀的情况
				return false;
			}
		}
		if (suffixPosition != suffix.length()) {
			// the expressionString ran out before the suffix could entirely be found
			// TODO 在模版嵌套时, 如果前缀所匹配的后缀不是当前表达式中最后一个, 则表示匹配错误
			return false;
		}
		return true;
	}

	/**
	 * Copes with nesting, for example '${...${...}}' where the correct end for the first
	 * ${ is the final }.
	 * @param suffix the suffix
	 * @param expressionString the expression string
	 * @param afterPrefixIndex the most recently found prefix location for which the
	 * matching end suffix is being sought
	 * @return the position of the correct matching nextSuffix or -1 if none can be found
	 */
	private int skipToCorrectEndSuffix(String suffix, String expressionString, int afterPrefixIndex)
			throws ParseException {

		// Chew on the expression text - relying on the rules:
		// brackets must be in pairs: () [] {}
		// string literals are "..." or '...' and these may contain unmatched brackets
		// TODO 当前位置的索引, 值为前缀后面的索引位置, 即, 表达式的起始位置
		int pos = afterPrefixIndex;
		// TODO 表达式长度
		int maxlen = expressionString.length();
		// TODO 因为会出现模板嵌套的问题, 即, '${...${...}}'的情况,所以需要确定一下下一个后缀的位置
		int nextSuffix = expressionString.indexOf(suffix, afterPrefixIndex);
		if (nextSuffix == -1) {
			return -1; // the suffix is missing
		}
		Deque<Bracket> stack = new ArrayDeque<>();
		while (pos < maxlen) {
			if (isSuffixHere(expressionString, pos, suffix) && stack.isEmpty()) {
				// TODO 匹配到正确的后缀时, 如果栈是空的, 直接跳过
				break;
			}
			char ch = expressionString.charAt(pos);
			switch (ch) {
				case '{':
				case '[':
				case '(':
					// TODO 左括号入栈
					stack.push(new Bracket(ch, pos));
					break;
				case '}':
				case ']':
				case ')':
					if (stack.isEmpty()) {
						// TODO 如果解析到右括号, 但栈里却没有左括号时, 抛出解析异常
						throw new ParseException(expressionString, pos, "Found closing '" + ch +
								"' at position " + pos + " without an opening '" +
								Bracket.theOpenBracketFor(ch) + "'");
					}
					// TODO 正常情况弹出之前的左括号
					Bracket p = stack.pop();
					if (!p.compatibleWithCloseBracket(ch)) {
						// TODO 如果弹出来的不是左括号, 同样抛出异常
						throw new ParseException(expressionString, pos, "Found closing '" + ch +
								"' at position " + pos + " but most recent opening is '" + p.bracket +
								"' at position " + p.pos);
					}
					break;
				case '\'':
				case '"':
					// jump to the end of the literal
					// TODO 遇到双引号'"', 或单引号'''时, 表示遇到了一个字面量, 这时取这个字面量结束的位置(拿双引号或单引号的位置取就行)
					int endLiteral = expressionString.indexOf(ch, pos + 1);
					if (endLiteral == -1) {
						throw new ParseException(expressionString, pos,
								"Found non terminating string literal starting at position " + pos);
					}
					// TODO 设置当前位置的索引
					pos = endLiteral;
					break;
			}
			// TODO 跳过后面的引号
			pos++;
		}
		if (!stack.isEmpty()) {
			// TODO 括号都是成对出现的, 上面的处理也会是成对入栈出栈. 到这时, 栈应该是空的, 如果还有括号存在, 则解析出现错误
			Bracket p = stack.pop();
			throw new ParseException(expressionString, p.pos, "Missing closing '" +
					Bracket.theCloseBracketFor(p.bracket) + "' for '" + p.bracket + "' at position " + p.pos);
		}
		if (!isSuffixHere(expressionString, pos, suffix)) {
			// TODO 后缀匹配失败的情况
			return -1;
		}
		// TODO 这里返回的是前缀对应的后缀的位置, 比如: '${...${...}}', 第一个'${'应该匹配最后一个'}', 第二个'${'应该匹配倒数第二个'}'
		return pos;
	}


	/**
	 * Actually parse the expression string and return an Expression object.
	 * @param expressionString the raw expression string to parse
	 * @param context a context for influencing this expression parsing routine (optional)
	 * @return an evaluator for the parsed expression
	 * @throws ParseException an exception occurred during parsing
	 */
	protected abstract Expression doParseExpression(String expressionString, @Nullable ParserContext context)
			throws ParseException;


	/**
	 * This captures a type of bracket and the position in which it occurs in the
	 * expression. The positional information is used if an error has to be reported
	 * because the related end bracket cannot be found. Bracket is used to describe:
	 * square brackets [] round brackets () and curly brackets {}
	 */
	private static class Bracket {

		char bracket;

		int pos;

		Bracket(char bracket, int pos) {
			this.bracket = bracket;
			this.pos = pos;
		}

		boolean compatibleWithCloseBracket(char closeBracket) {
			if (this.bracket == '{') {
				return closeBracket == '}';
			}
			else if (this.bracket == '[') {
				return closeBracket == ']';
			}
			return closeBracket == ')';
		}

		static char theOpenBracketFor(char closeBracket) {
			if (closeBracket == '}') {
				return '{';
			}
			else if (closeBracket == ']') {
				return '[';
			}
			return '(';
		}

		static char theCloseBracketFor(char openBracket) {
			if (openBracket == '{') {
				return '}';
			}
			else if (openBracket == '[') {
				return ']';
			}
			return ')';
		}
	}

}
