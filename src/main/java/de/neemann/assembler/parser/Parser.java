package de.neemann.assembler.parser;

import de.neemann.assembler.asm.*;
import de.neemann.assembler.expression.*;
import de.neemann.assembler.parser.macros.Pop;
import de.neemann.assembler.parser.macros.Push;
import de.neemann.assembler.parser.macros.SCall;
import de.neemann.assembler.parser.macros.SRet;

import java.io.*;
import java.util.HashMap;

import static java.io.StreamTokenizer.*;

/**
 * @author hneemann
 */
public class Parser implements Closeable {
    private final StreamTokenizer tokens;
    private final Reader in;
    private final HashMap<String, Macro> macros;

    public Parser(String source) {
        this(new StringReader(source));
    }

    public Parser(Reader in) {
        this.in = in;
        this.macros = new HashMap<>();
        tokens = new StreamTokenizer(in);
        tokens.eolIsSignificant(true);
        tokens.ordinaryChar('-');
        tokens.ordinaryChar('.');
        tokens.ordinaryChars('0', '9');
        tokens.wordChars('0', '9');
        tokens.wordChars('.', '.');

        addMacro(new Push());
        addMacro(new Pop());
        addMacro(new SCall());
        addMacro(new SRet());
    }

    public void addMacro(Macro m) {
        macros.put(m.getName().toLowerCase(), m);
    }

    public Program getProgram() throws IOException, ParserException, InstructionException, ExpressionException {
        Program p = new Program();

        WHILE:
        while (true) {
            switch (tokens.nextToken()) {
                case TT_WORD:
                    String t = tokens.sval;
                    if (t.startsWith(".")) {
                        parseMetaCommand(p, t);
                    } else {
                        if (Opcode.parseStr(t) == null && !macros.containsKey(t)) {
                            p.setPendingLabel(t);
                            consume(':');
                            t = parseWord();
                        }
                        if (macros.containsKey(t.toLowerCase())) {
                            macros.get(t.toLowerCase()).parseMacro(p, t, this);
                        } else
                            parseInstruction(p, t);
                    }
                    switch (tokens.nextToken()) {
                        case ';':
                            skipLine();
                            break;
                        case TT_EOF:
                        case TT_EOL:
                            break;
                        default:
                            throw makeParserException("unexpected token " + tokens);
                    }
                    break;
                case ';':
                    skipLine();
                    break;
                case TT_EOL:
                    break;
                case TT_EOF:
                    break WHILE;
                default:
                    throw makeParserException("unexpected token '" + tokens + "'");
            }
        }

        return p;
    }

    private void skipLine() throws IOException {
        int to;
        do {
            to = tokens.nextToken();
        } while (to != TT_EOF && to != TT_EOL);
    }

    private void parseMetaCommand(Program p, String t) throws IOException, ParserException, ExpressionException {
        switch (t) {
            case ".word":
                p.addRam(parseWord(), 1);
                break;
            case ".long":
                p.addRam(parseWord(), 2);
                break;
            case ".const":
                p.getContext().addIdentifier(parseWord(), parseExpression().getValue(p.getContext()));
                break;
            case ".data":
                String ident = parseWord();
                int addr = p.addRam(ident, 0);
                p.addData(addr++, parseExpression().getValue(p.getContext()));
                while (isNext(',')) {
                    isNext(TT_EOL);
                    p.addData(addr++, parseExpression().getValue(p.getContext()));
                }
                break;
            default:
                throw makeParserException("unknown meta command " + t);
        }
    }

    private void parseInstruction(Program p, String t) throws IOException, ParserException, InstructionException {
        Opcode opcode = Opcode.parseStr(t);
        if (opcode == null)
            throw makeParserException("opcode expected, found '" + t + "'");

        int line = tokens.lineno();

        Register dest = null;
        Register source = null;
        switch (opcode.getRegsNeeded()) {
            case both:
                dest = parseReg();
                consume(',');
                source = parseReg();
                break;
            case none:
                break;
            default:
                dest = parseReg();
                break;
        }

        Expression constant = null;
        if (opcode.getImmedNeeded() == Opcode.ImmedNeeded.Yes) {
            if (opcode.getRegsNeeded() != Opcode.RegsNeeded.none)
                consume(',');
            constant = parseExpression();
        }

        Instruction i;
        switch (opcode.getRegsNeeded()) {
            case both:
                if (constant != null)
                    i = Instruction.make(opcode, dest, source, constant);
                else
                    i = Instruction.make(opcode, dest, source);
                break;
            case none:
                i = Instruction.make(opcode, constant);
                break;
            default:
                if (constant != null)
                    i = Instruction.make(opcode, dest, constant);
                else
                    i = Instruction.make(opcode, dest);
                break;
        }
        if (i == null)
            throw makeParserException("illegal state: No opcode");

        i.setLineNumber(line);
        p.add(i);
    }

    public void consume(int c) throws IOException, ParserException {
        if (tokens.nextToken() != c)
            throw makeParserException("expected '" + (char) c + "', found '" + tokens + "'");
    }

    public Register parseReg() throws IOException, ParserException {
        String r = parseWord();
        Register reg = Register.parseStr(r);
        if (reg == null)
            throw makeParserException("expected a register, found '" + r + "'");
        return reg;
    }

    public String parseWord() throws IOException, ParserException {
        int t = tokens.nextToken();
        if (t != TT_WORD)
            throw makeParserException("unexpected number or EOL/EOF!");
        return tokens.sval;
    }

    public ParserException makeParserException(String message) {
        return new ParserException(message, tokens.lineno());
    }


    @Override
    public void close() throws IOException {
        in.close();
    }

    public boolean isNext(String str) throws IOException {
        int t = tokens.nextToken();
        if (t == TT_WORD && tokens.sval.equalsIgnoreCase(str))
            return true;

        tokens.pushBack();
        return false;
    }

    public boolean isNext(int c) throws IOException {
        int t = tokens.nextToken();
        if (t == c)
            return true;

        tokens.pushBack();
        return false;
    }

    public Expression getExpression() throws IOException, ParserException {
        Expression exp = parseExpression();
        if (tokens.nextToken() != TT_EOF)
            throw makeParserException("no EOF found, but " + tokens);
        return exp;
    }

    public Expression parseExpression() throws IOException, ParserException {
        Expression ex = parseAnd();
        while (isNext("or")) {
            ex = new Operate(ex, Operate.Operation.OR, parseAnd());
        }
        return ex;
    }


    private Expression parseAnd() throws IOException, ParserException {
        Expression ex = parseXOR();
        while (isNext("and")) {
            ex = new Operate(ex, Operate.Operation.AND, parseXOR());
        }
        return ex;
    }

    private Expression parseXOR() throws IOException, ParserException {
        Expression ex = parseADD();
        while (isNext("xor")) {
            ex = new Operate(ex, Operate.Operation.XOR, parseADD());
        }
        return ex;
    }

    private Expression parseADD() throws IOException, ParserException {
        Expression ex = parseSUB();
        while (isNext('+')) {
            ex = new Operate(ex, Operate.Operation.ADD, parseSUB());
        }
        return ex;
    }

    private Expression parseSUB() throws IOException, ParserException {
        Expression ex = parseMUL();
        while (isNext('-')) {
            ex = new Operate(ex, Operate.Operation.SUB, parseMUL());
        }
        return ex;
    }

    private Expression parseMUL() throws IOException, ParserException {
        Expression ex = parseValue();
        while (isNext('*')) {
            ex = new Operate(ex, Operate.Operation.MUL, parseValue());
        }
        return ex;
    }

    private Expression parseValue() throws IOException, ParserException {
        switch (tokens.nextToken()) {
            case TT_WORD:
                String s = tokens.sval;
                char c = s.charAt(0);
                if (c >= '0' && c <= '9') {
                    return new Constant(parseInteger(s.toLowerCase()));
                } else
                    return new Identifier(tokens.sval);
            case '(':
                Expression ex = parseExpression();
                consume(')');
                return ex;
            case '-':
                return new Neg(parseExpression());
            case '~':
                return new Not(parseExpression());
        }
        throw makeParserException("unexpected token " + tokens);
    }

    private int parseInteger(String s) throws ParserException {
        try {
            if (s.startsWith("0b"))
                return Integer.parseInt(s.substring(2), 2);
            else if (s.startsWith("0x"))
                return Integer.parseInt(s.substring(2), 16);
            else return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw makeParserException(s + " is not a number");
        }
    }


}
