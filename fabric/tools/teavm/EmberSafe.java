package org.teavm.model.transformation;

import org.teavm.model.BasicBlock;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.RaiseInstruction;
import org.teavm.model.instructions.StringConstantInstruction;

/**
 * Added to TeaVM by Ember. If TeaVM's own bookkeeping breaks on one method
 * (seen after it cuts out calls to Java features the browser lacks), name the
 * method in the log and turn it into "throw NoClassDefFoundError" instead of
 * stopping the whole build. The method then fails only if it is called.
 */
public final class EmberSafe {
    private EmberSafe() {
    }

    public static void nullChecks(NullCheckInsertion self, Program program, MethodReference method) {
        try {
            self.transformProgram(program, method);
        } catch (AssertionError | RuntimeException e) {
            System.out.println("[ember] TeaVM could not compile " + method + " (" + e.getMessage()
                    + "); it now throws when called");
            stub(program, method);
        }
    }

    static void stub(Program program, MethodReference method) {
        BasicBlock entry = program.basicBlockAt(0);
        for (int i = program.basicBlockCount() - 1; i > 0; --i) {
            program.deleteBasicBlock(i);
        }
        entry.removeAllInstructions();
        entry.getPhis().clear();
        entry.getTryCatchBlocks().clear();

        String cls = NoClassDefFoundError.class.getName();
        var ex = program.createVariable();
        var construct = new ConstructInstruction();
        construct.setType(cls);
        construct.setReceiver(ex);
        entry.add(construct);

        var text = program.createVariable();
        var constant = new StringConstantInstruction();
        constant.setConstant("Ember: not available in the browser: " + method);
        constant.setReceiver(text);
        entry.add(constant);

        var init = new InvokeInstruction();
        init.setInstance(ex);
        init.setMethod(new MethodReference(cls, "<init>", ValueType.object("java.lang.String"), ValueType.VOID));
        init.setType(InvocationType.SPECIAL);
        init.setArguments(text);
        entry.add(init);

        var raise = new RaiseInstruction();
        raise.setException(ex);
        entry.add(raise);
        program.pack();
    }
}
