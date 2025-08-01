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

package org.springframework.expression.spel.standard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.expression.spel.InternalParseException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.SpelParseException;

/**
 * Lex some input data into a stream of tokens that can then be parsed.
 *
 * @author Andy Clement
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 3.0
 */
class Tokenizer {

	/**
	 * Alternative textual operator names which must match enum constant names
	 * in {@link TokenKind}.
	 * <p>Note that {@code AND} and {@code OR} are also alternative textual
	 * names, but they are handled later in {@link InternalSpelExpressionParser}.
	 * <p>If this list gets changed, it must remain sorted since we use it with
	 * {@link Arrays#binarySearch(Object[], Object)}.
	 */
	private static final String[] ALTERNATIVE_OPERATOR_NAMES =
			{"DIV", "EQ", "GE", "GT", "LE", "LT", "MOD", "NE", "NOT"};

	private static final byte[] FLAGS = new byte[256];

	private static final byte IS_DIGIT = 0x01;

	private static final byte IS_HEXDIGIT = 0x02;

	static {
		for (int ch = '0'; ch <= '9'; ch++) {
			FLAGS[ch] |= IS_DIGIT | IS_HEXDIGIT;
		}
		for (int ch = 'A'; ch <= 'F'; ch++) {
			FLAGS[ch] |= IS_HEXDIGIT;
		}
		for (int ch = 'a'; ch <= 'f'; ch++) {
			FLAGS[ch] |= IS_HEXDIGIT;
		}
	}


	private final String expressionString;

	private final char[] charsToProcess;

	private int pos;

	private final int max;

	private final List<Token> tokens = new ArrayList<>();


	public Tokenizer(String inputData) {
		this.expressionString = inputData;
		this.charsToProcess = (inputData + "\0").toCharArray();
		this.max = this.charsToProcess.length;
		this.pos = 0;
	}


	public List<Token> process() {
		while (this.pos < this.max) {
			// TODO 从头开始挨个儿处理表达式中的每个字符, 为每个操作符和操作数生成token, 放入tokens
			char ch = this.charsToProcess[this.pos];
			if (isAlphabetic(ch)) {
				// TODO 字母开头的情况做词法分析, 做了两件事:
				//  1. 将SpEL定义的关系运算符替换成对应的关系运算符, 生成token后放入tokens
				//  2. 其他情况下, 直接生成IDENTIFIER类型的token放入tokens
				lexIdentifier();
			}
			else {
				// TODO 处理其他操作符, 生成对应的token放入tokens
				switch (ch) {
					case '+':
						if (isTwoCharToken(TokenKind.INC)) {
							pushPairToken(TokenKind.INC);
						}
						else {
							pushCharToken(TokenKind.PLUS);
						}
						break;
					case '_':
						// TODO '_'开头的情况也要做词法分析
						lexIdentifier();  // '_' is another way to start an identifier
						break;
					case '-':
						if (isTwoCharToken(TokenKind.DEC)) {
							pushPairToken(TokenKind.DEC);
						}
						else {
							pushCharToken(TokenKind.MINUS);
						}
						break;
					case ':':
						pushCharToken(TokenKind.COLON);
						break;
					case '.':
						pushCharToken(TokenKind.DOT);
						break;
					case ',':
						pushCharToken(TokenKind.COMMA);
						break;
					case '*':
						pushCharToken(TokenKind.STAR);
						break;
					case '/':
						pushCharToken(TokenKind.DIV);
						break;
					case '%':
						pushCharToken(TokenKind.MOD);
						break;
					case '(':
						pushCharToken(TokenKind.LPAREN);
						break;
					case ')':
						pushCharToken(TokenKind.RPAREN);
						break;
					case '[':
						pushCharToken(TokenKind.LSQUARE);
						break;
					case '#':
						pushCharToken(TokenKind.HASH);
						break;
					case ']':
						pushCharToken(TokenKind.RSQUARE);
						break;
					case '{':
						pushCharToken(TokenKind.LCURLY);
						break;
					case '}':
						pushCharToken(TokenKind.RCURLY);
						break;
					case '@':
						pushCharToken(TokenKind.BEAN_REF);
						break;
					case '^':
						if (isTwoCharToken(TokenKind.SELECT_FIRST)) {
							pushPairToken(TokenKind.SELECT_FIRST);
						}
						else {
							pushCharToken(TokenKind.POWER);
						}
						break;
					case '!':
						if (isTwoCharToken(TokenKind.NE)) {
							pushPairToken(TokenKind.NE);
						}
						else if (isTwoCharToken(TokenKind.PROJECT)) {
							pushPairToken(TokenKind.PROJECT);
						}
						else {
							pushCharToken(TokenKind.NOT);
						}
						break;
					case '=':
						if (isTwoCharToken(TokenKind.EQ)) {
							pushPairToken(TokenKind.EQ);
						}
						else {
							pushCharToken(TokenKind.ASSIGN);
						}
						break;
					case '&':
						if (isTwoCharToken(TokenKind.SYMBOLIC_AND)) {
							pushPairToken(TokenKind.SYMBOLIC_AND);
						}
						else {
							pushCharToken(TokenKind.FACTORY_BEAN_REF);
						}
						break;
					case '|':
						if (!isTwoCharToken(TokenKind.SYMBOLIC_OR)) {
							raiseParseException(this.pos, SpelMessage.MISSING_CHARACTER, "|");
						}
						pushPairToken(TokenKind.SYMBOLIC_OR);
						break;
					case '?':
						if (isTwoCharToken(TokenKind.SELECT)) {
							pushPairToken(TokenKind.SELECT);
						}
						else if (isTwoCharToken(TokenKind.ELVIS)) {
							pushPairToken(TokenKind.ELVIS);
						}
						else if (isTwoCharToken(TokenKind.SAFE_NAVI)) {
							pushPairToken(TokenKind.SAFE_NAVI);
						}
						else {
							pushCharToken(TokenKind.QMARK);
						}
						break;
					case '$':
						if (isTwoCharToken(TokenKind.SELECT_LAST)) {
							pushPairToken(TokenKind.SELECT_LAST);
						}
						else {
							lexIdentifier();  // '$' is another way to start an identifier
						}
						break;
					case '>':
						if (isTwoCharToken(TokenKind.GE)) {
							pushPairToken(TokenKind.GE);
						}
						else {
							pushCharToken(TokenKind.GT);
						}
						break;
					case '<':
						if (isTwoCharToken(TokenKind.LE)) {
							pushPairToken(TokenKind.LE);
						}
						else {
							pushCharToken(TokenKind.LT);
						}
						break;
					case '0':
					case '1':
					case '2':
					case '3':
					case '4':
					case '5':
					case '6':
					case '7':
					case '8':
					case '9':
						lexNumericLiteral(ch == '0');
						break;
					case ' ':
					case '\t':
					case '\r':
					case '\n':
						// drift over white space
						this.pos++;
						break;
					case '\'':
						lexQuotedStringLiteral();
						break;
					case '"':
						lexDoubleQuotedStringLiteral();
						break;
					case 0:
						// hit sentinel at end of value
						this.pos++;  // will take us to the end
						break;
					case '\\':
						raiseParseException(this.pos, SpelMessage.UNEXPECTED_ESCAPE_CHAR);
						break;
					default:
						raiseParseException(this.pos + 1, SpelMessage.UNSUPPORTED_CHARACTER, ch, (int) ch);
				}
			}
		}
		return this.tokens;
	}


	// STRING_LITERAL: '\''! (APOS|~'\'')* '\''!;
	private void lexQuotedStringLiteral() {
		int start = this.pos;
		boolean terminated = false;
		while (!terminated) {
			this.pos++;
			char ch = this.charsToProcess[this.pos];
			if (ch == '\'') {
				// may not be the end if the char after is also a '
				if (this.charsToProcess[this.pos + 1] == '\'') {
					this.pos++;  // skip over that too, and continue
				}
				else {
					terminated = true;
				}
			}
			if (isExhausted()) {
				raiseParseException(start, SpelMessage.NON_TERMINATING_QUOTED_STRING);
			}
		}
		this.pos++;
		this.tokens.add(new Token(TokenKind.LITERAL_STRING, subarray(start, this.pos), start, this.pos));
	}

	// DQ_STRING_LITERAL: '"'! (~'"')* '"'!;
	private void lexDoubleQuotedStringLiteral() {
		int start = this.pos;
		boolean terminated = false;
		while (!terminated) {
			this.pos++;
			char ch = this.charsToProcess[this.pos];
			if (ch == '"') {
				// may not be the end if the char after is also a "
				if (this.charsToProcess[this.pos + 1] == '"') {
					this.pos++;  // skip over that too, and continue
				}
				else {
					terminated = true;
				}
			}
			if (isExhausted()) {
				raiseParseException(start, SpelMessage.NON_TERMINATING_DOUBLE_QUOTED_STRING);
			}
		}
		this.pos++;
		this.tokens.add(new Token(TokenKind.LITERAL_STRING, subarray(start, this.pos), start, this.pos));
	}

	// REAL_LITERAL :
	// ('.' (DECIMAL_DIGIT)+ (EXPONENT_PART)? (REAL_TYPE_SUFFIX)?) |
	// ((DECIMAL_DIGIT)+ '.' (DECIMAL_DIGIT)+ (EXPONENT_PART)? (REAL_TYPE_SUFFIX)?) |
	// ((DECIMAL_DIGIT)+ (EXPONENT_PART) (REAL_TYPE_SUFFIX)?) |
	// ((DECIMAL_DIGIT)+ (REAL_TYPE_SUFFIX));
	// fragment INTEGER_TYPE_SUFFIX : ( 'L' | 'l' );
	// fragment HEX_DIGIT :
	// '0'|'1'|'2'|'3'|'4'|'5'|'6'|'7'|'8'|'9'|'A'|'B'|'C'|'D'|'E'|'F'|'a'|'b'|'c'|'d'|'e'|'f';
	//
	// fragment EXPONENT_PART : 'e' (SIGN)* (DECIMAL_DIGIT)+ | 'E' (SIGN)*
	// (DECIMAL_DIGIT)+ ;
	// fragment SIGN : '+' | '-' ;
	// fragment REAL_TYPE_SUFFIX : 'F' | 'f' | 'D' | 'd';
	// INTEGER_LITERAL
	// : (DECIMAL_DIGIT)+ (INTEGER_TYPE_SUFFIX)?;

	private void lexNumericLiteral(boolean firstCharIsZero) {
		boolean isReal = false;
		int start = this.pos;
		char ch = this.charsToProcess[this.pos + 1];
		boolean isHex = ch == 'x' || ch == 'X';

		// deal with hexadecimal
		if (firstCharIsZero && isHex) {
			this.pos = this.pos + 1;
			do {
				this.pos++;
			}
			while (isHexadecimalDigit(this.charsToProcess[this.pos]));
			if (isChar('L', 'l')) {
				pushHexIntToken(subarray(start + 2, this.pos), true, start, this.pos);
				this.pos++;
			}
			else {
				pushHexIntToken(subarray(start + 2, this.pos), false, start, this.pos);
			}
			return;
		}

		// real numbers must have leading digits

		// Consume first part of number
		do {
			this.pos++;
		}
		while (isDigit(this.charsToProcess[this.pos]));

		// a '.' indicates this number is a real
		ch = this.charsToProcess[this.pos];
		if (ch == '.') {
			isReal = true;
			int dotpos = this.pos;
			// carry on consuming digits
			do {
				this.pos++;
			}
			while (isDigit(this.charsToProcess[this.pos]));
			if (this.pos == dotpos + 1) {
				// the number is something like '3.'. It is really an int but may be
				// part of something like '3.toString()'. In this case process it as
				// an int and leave the dot as a separate token.
				this.pos = dotpos;
				pushIntToken(subarray(start, this.pos), false, start, this.pos);
				return;
			}
		}

		int endOfNumber = this.pos;

		// Now there may or may not be an exponent

		// Is it a long ?
		if (isChar('L', 'l')) {
			if (isReal) {  // 3.4L - not allowed
				raiseParseException(start, SpelMessage.REAL_CANNOT_BE_LONG);
			}
			pushIntToken(subarray(start, endOfNumber), true, start, endOfNumber);
			this.pos++;
		}
		else if (isExponentChar(this.charsToProcess[this.pos])) {
			isReal = true;  // if it wasn't before, it is now
			this.pos++;
			char possibleSign = this.charsToProcess[this.pos];
			if (isSign(possibleSign)) {
				this.pos++;
			}

			// exponent digits
			do {
				this.pos++;
			}
			while (isDigit(this.charsToProcess[this.pos]));
			boolean isFloat = false;
			if (isFloatSuffix(this.charsToProcess[this.pos])) {
				isFloat = true;
				endOfNumber = ++this.pos;
			}
			else if (isDoubleSuffix(this.charsToProcess[this.pos])) {
				endOfNumber = ++this.pos;
			}
			pushRealToken(subarray(start, this.pos), isFloat, start, this.pos);
		}
		else {
			ch = this.charsToProcess[this.pos];
			boolean isFloat = false;
			if (isFloatSuffix(ch)) {
				isReal = true;
				isFloat = true;
				endOfNumber = ++this.pos;
			}
			else if (isDoubleSuffix(ch)) {
				isReal = true;
				endOfNumber = ++this.pos;
			}
			if (isReal) {
				pushRealToken(subarray(start, endOfNumber), isFloat, start, endOfNumber);
			}
			else {
				pushIntToken(subarray(start, endOfNumber), false, start, endOfNumber);
			}
		}
	}

	private void lexIdentifier() {
		// TODO 以当前处理的字符位置做为起始位置
		int start = this.pos;
		// TODO 顺序分析每一个字符, 找到表达式中每个关键字, 非字通母, 数字, '_', '$'时, 表示后面的字符是另一个单独的关键字
		do {
			this.pos++;
		}
		while (isIdentifier(this.charsToProcess[this.pos]));
		// TODO 取出当前要处理的关键字
		char[] subarray = subarray(start, this.pos);

		// Check if this is the alternative (textual) representation of an operator (see
		// ALTERNATIVE_OPERATOR_NAMES).
		if (subarray.length == 2 || subarray.length == 3) {
			// TODO 操作符长度为2, 或3时, 需要判断一下是否为SpEL定义的关系运算符, 如果是, 则用对应的关系运算符进行替换:
			//  DIV -> '/', EQ -> '==', GE -> '>=', GT -> '>', LE -> '<=', LT -> '<', MOD -> '%', NE -> '!=', NOT -> '!'
			String asString = new String(subarray).toUpperCase(Locale.ROOT);
			int idx = Arrays.binarySearch(ALTERNATIVE_OPERATOR_NAMES, asString);
			if (idx >= 0) {
				// TODO 如果是, 生成对应的关系运算符类型的token放入tokens
				pushOneCharOrTwoCharToken(TokenKind.valueOf(asString), start, subarray);
				return;
			}
		}
		// TODO 其他情况, 直接以标识符(identifier)类型, 放入tokens
		this.tokens.add(new Token(TokenKind.IDENTIFIER, subarray, start, this.pos));
	}

	private void pushIntToken(char[] data, boolean isLong, int start, int end) {
		if (isLong) {
			this.tokens.add(new Token(TokenKind.LITERAL_LONG, data, start, end));
		}
		else {
			this.tokens.add(new Token(TokenKind.LITERAL_INT, data, start, end));
		}
	}

	private void pushHexIntToken(char[] data, boolean isLong, int start, int end) {
		if (data.length == 0) {
			if (isLong) {
				raiseParseException(start, SpelMessage.NOT_A_LONG, this.expressionString.substring(start, end + 1));
			}
			else {
				raiseParseException(start, SpelMessage.NOT_AN_INTEGER, this.expressionString.substring(start, end));
			}
		}
		if (isLong) {
			this.tokens.add(new Token(TokenKind.LITERAL_HEXLONG, data, start, end));
		}
		else {
			this.tokens.add(new Token(TokenKind.LITERAL_HEXINT, data, start, end));
		}
	}

	private void pushRealToken(char[] data, boolean isFloat, int start, int end) {
		if (isFloat) {
			this.tokens.add(new Token(TokenKind.LITERAL_REAL_FLOAT, data, start, end));
		}
		else {
			this.tokens.add(new Token(TokenKind.LITERAL_REAL, data, start, end));
		}
	}

	private char[] subarray(int start, int end) {
		return Arrays.copyOfRange(this.charsToProcess, start, end);
	}

	/**
	 * Check if this might be a two character token.
	 */
	private boolean isTwoCharToken(TokenKind kind) {
		return (kind.tokenChars.length == 2 &&
				this.charsToProcess[this.pos] == kind.tokenChars[0] &&
				this.charsToProcess[this.pos + 1] == kind.tokenChars[1]);
	}

	/**
	 * Push a token of just one character in length.
	 */
	private void pushCharToken(TokenKind kind) {
		this.tokens.add(new Token(kind, this.pos, this.pos + 1));
		this.pos++;
	}

	/**
	 * Push a token of two characters in length.
	 */
	private void pushPairToken(TokenKind kind) {
		this.tokens.add(new Token(kind, this.pos, this.pos + 2));
		this.pos += 2;
	}

	private void pushOneCharOrTwoCharToken(TokenKind kind, int pos, char[] data) {
		this.tokens.add(new Token(kind, data, pos, pos + kind.getLength()));
	}

	// ID: ('a'..'z'|'A'..'Z'|'_'|'$') ('a'..'z'|'A'..'Z'|'_'|'$'|'0'..'9'|DOT_ESCAPED)*;
	private boolean isIdentifier(char ch) {
		return isAlphabetic(ch) || isDigit(ch) || ch == '_' || ch == '$';
	}

	private boolean isChar(char a, char b) {
		char ch = this.charsToProcess[this.pos];
		return ch == a || ch == b;
	}

	private boolean isExponentChar(char ch) {
		return ch == 'e' || ch == 'E';
	}

	private boolean isFloatSuffix(char ch) {
		return ch == 'f' || ch == 'F';
	}

	private boolean isDoubleSuffix(char ch) {
		return ch == 'd' || ch == 'D';
	}

	private boolean isSign(char ch) {
		return ch == '+' || ch == '-';
	}

	private boolean isDigit(char ch) {
		if (ch > 255) {
			return false;
		}
		return (FLAGS[ch] & IS_DIGIT) != 0;
	}

	private boolean isAlphabetic(char ch) {
		return Character.isLetter(ch);
	}

	private boolean isHexadecimalDigit(char ch) {
		if (ch > 255) {
			return false;
		}
		return (FLAGS[ch] & IS_HEXDIGIT) != 0;
	}

	private boolean isExhausted() {
		return (this.pos == this.max - 1);
	}

	private void raiseParseException(int start, SpelMessage msg, Object... inserts) {
		throw new InternalParseException(new SpelParseException(this.expressionString, start, msg, inserts));
	}

}
