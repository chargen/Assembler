package de.neemann.assembler.parser.macros;

import de.neemann.assembler.asm.*;
import de.neemann.assembler.expression.ExpressionException;
import de.neemann.assembler.parser.Macro;
import de.neemann.assembler.parser.Parser;
import de.neemann.assembler.parser.ParserException;

import java.io.IOException;

/**
 * @author hneemann
 */
public class Inc extends Macro {

    /**
     * Creates a new instance
     */
    public Inc() {
        super("INC", MnemonicArguments.DEST, "increases the given register by one");
    }

    @Override
    public void parseMacro(Program p, String name, Parser parser) throws IOException, ParserException, InstructionException, ExpressionException {
        Register r = parser.parseReg();
        p.setPendingMacroDescription(getName() + " " + r.name());
        p.add(new InstructionBuilder(Opcode.ADDIs).setDest(r).setConstant(1).build());
    }
}
