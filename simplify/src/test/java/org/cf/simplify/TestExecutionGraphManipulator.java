package org.cf.simplify;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import gnu.trove.list.TIntList;
import gnu.trove.list.linked.TIntLinkedList;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.cf.smalivm.context.ExecutionNode;
import org.cf.smalivm.context.HeapItem;
import org.cf.smalivm.context.MethodState;
import org.cf.smalivm.opcode.Op;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.Label;
import org.jf.dexlib2.builder.MethodLocation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21s;
import org.jf.dexlib2.builder.instruction.BuilderInstruction30t;
import org.junit.Test;

public class TestExecutionGraphManipulator {

    private static final String CLASS_NAME = "Lexecution_graph_manipulator_test;";

    private ExecutionGraphManipulator manipulator;

    @Test
    public void testAddingInstructionModifiesStateCorrectly() {
        //@formatter:off
        Object[][] expected = new Object[][] {
                        { 0, Opcode.NOP, new Object[][][] { { { 1, Opcode.CONST_4 } } } },
                        { 1, Opcode.CONST_4, new Object[][][] { { { 2, Opcode.CONST_4 } } } },
                        { 2, Opcode.CONST_4, new Object[][][] { { { 3, Opcode.CONST_4 } } } },
                        { 3, Opcode.CONST_4, new Object[][][] { { { 4, Opcode.CONST_4 } } } },
                        { 4, Opcode.CONST_4, new Object[][][] { { { 5, Opcode.CONST_4 } } } },
                        { 5, Opcode.CONST_4, new Object[][][] { { { 6, Opcode.RETURN_VOID } } } },
                        { 6, Opcode.RETURN_VOID, new Object[1][0][0] },
        };
        //@formatter:on

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "verySimple()V");
        BuilderInstruction addition = new BuilderInstruction10x(Opcode.NOP);
        manipulator.addInstruction(0, addition);

        test(expected, manipulator);
        testHeritage(manipulator, 0);
        testHeritage(manipulator, 1);
    }

    @Test
    public void testAddingInstructionThatCausesNopPaddingToBeAddedModifiesStateCorrectly() {
        //@formatter:off
        Object[][] expected = new Object[][] {
                        { 0, Opcode.CONST_16, new Object[][][] { { { 2, Opcode.NEW_ARRAY } } } },
                        { 2, Opcode.NEW_ARRAY, new Object[][][] { { { 4, Opcode.NOP } } } },
                        { 4, Opcode.NOP, new Object[][][] { { { 5, Opcode.FILL_ARRAY_DATA } } } },
                        { 5, Opcode.FILL_ARRAY_DATA, new Object[][][] { { { 0xa, Opcode.ARRAY_PAYLOAD } } } },
                        { 8, Opcode.RETURN_VOID, new Object[1][0][0] },
                        { 9, Opcode.NOP, new Object[0][0][0] },
                        { 0xa, Opcode.ARRAY_PAYLOAD, new Object[][][] {
                                        { { 8, Opcode.RETURN_VOID } },
                        } },
        };
        //@formatter:on

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "hasNoNopPadding()V");
        BuilderInstruction addition = new BuilderInstruction10x(Opcode.NOP);
        manipulator.addInstruction(4, addition);

        test(expected, manipulator);
        testHeritage(manipulator, 2);
        testHeritage(manipulator, 4);
        testHeritage(manipulator, 5);
    }

    @Test
    public void testAddingManyNopsAfterGotoModifiesStateCorrectly() {
        int nops_to_insert = 127;

        Object[][] expected = new Object[3 + nops_to_insert][];
        expected[0] = new Object[] { 0, Opcode.GOTO_16, new Object[][][] { { { 2 + 1 + 127, Opcode.RETURN_VOID } } } };
        // No children, no node pile, these nops are dead code and never get executed
        expected[1] = new Object[] { 2, Opcode.NOP, new Object[0][0][0] };
        for (int i = 0; i < nops_to_insert; i++) {
            int index = i + 2;
            expected[index] = new Object[] { index + 1, Opcode.NOP, new Object[0][0][0] };
        }
        expected[129] = new Object[] { 130, Opcode.RETURN_VOID, new Object[1][0][0] };

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "hasGotoAndOneNop()V");

        // Adding 126 bytes (nop) between goto and target offset causes dexlib to "fix" goto into goto/16
        for (int i = 0; i < nops_to_insert - 1; i++) {
            manipulator.addInstruction(1, new BuilderInstruction10x(Opcode.NOP));
        }
        // Addresses 0 and 1 are now goto/16, need to insert at 2
        manipulator.addInstruction(2, new BuilderInstruction10x(Opcode.NOP));

        test(expected, manipulator);
    }

    @Test
    public void testAddingThenRemovingManyNopsAfterGotoModifiesStateCorrectly() {
        Object[][] expected = new Object[3][];
        expected[0] = new Object[] { 0, Opcode.GOTO_16, new Object[][][] { { { 3, Opcode.RETURN_VOID } } } };
        expected[1] = new Object[] { 2, Opcode.NOP, new Object[0][0][0] };
        expected[2] = new Object[] { 3, Opcode.RETURN_VOID, new Object[1][0][0] };

        int nops_to_insert = 127;

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "hasGotoAndOneNop()V");

        // Adding 126 bytes (nop) between goto and target offset causes dexlib to "fix" goto into goto/16
        for (int i = 0; i < nops_to_insert - 1; i++) {
            manipulator.addInstruction(1, new BuilderInstruction10x(Opcode.NOP));
        }
        // Addresses 0 and 1 are now goto/16, need to insert at 2
        manipulator.addInstruction(2, new BuilderInstruction10x(Opcode.NOP));

        TIntList removeList = new TIntLinkedList();
        // for (int removeAddress = 2, i = 0; i < nops_to_insert; removeAddress++, i++) {
        for (int i = 1; i < nops_to_insert; i++) {
            int removeAddress = i + 2;
            removeList.add(removeAddress);
        }
        manipulator.removeInstructions(removeList);
        manipulator.removeInstruction(2);

        test(expected, manipulator);
    }

    @Test
    public void testHasEveryRegisterAvailableAtEveryAddress() {
        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "verySimple()V");
        int[] addresses = manipulator.getAddresses();
        int[] expectedAvailable = new int[] { 0, 1, 2, 3, 4, };
        for (int address : addresses) {
            int[] actualAvailable = manipulator.getAvailableRegisters(address).toArray();
            Arrays.sort(actualAvailable);
            assertArrayEquals(expectedAvailable, actualAvailable);
        }
    }

    @Test
    public void testHasExpectedBasicProperties() {
        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "verySimple()V");

        int[] expectedAddresses = new int[] { 0, 1, 2, 3, 4, 5, };
        int[] actualAddresses = manipulator.getAddresses();
        assertArrayEquals(expectedAddresses, actualAddresses);
    }

    @Test
    public void testRemoveInstructionThatCausesNopPaddingToBeRemovedAndHasParentWhichWModifiesStateCorrectly() {
        //@formatter:off
        Object[][] expected = new Object[][] {
                        { 0, Opcode.CONST_16, new Object[][][] { { { 2, Opcode.NEW_ARRAY } } } },
                        { 2, Opcode.NEW_ARRAY, new Object[][][] { { { 4, Opcode.FILL_ARRAY_DATA } } } },
                        { 4, Opcode.FILL_ARRAY_DATA, new Object[][][] { { { 8, Opcode.ARRAY_PAYLOAD } } } },
                        { 7, Opcode.RETURN_VOID, new Object[1][0][0] },
                        { 8, Opcode.ARRAY_PAYLOAD, new Object[][][] { { { 7, Opcode.RETURN_VOID } } } },
        };
        //@formatter:on

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "hasNopPadding()V");
        manipulator.removeInstruction(4);

        test(expected, manipulator);
        testHeritage(manipulator, 2);
        testHeritage(manipulator, 4);
    }

    @Test
    public void testRemoveInstructionWithNoParentModifiesStateCorrectly() {
        //@formatter:off
        Object[][] expected = new Object[][] {
                        { 0, Opcode.CONST_4, new Object[][][] { { { 1, Opcode.CONST_4 } } } },
                        { 1, Opcode.CONST_4, new Object[][][] { { { 2, Opcode.CONST_4 } } } },
                        { 2, Opcode.CONST_4, new Object[][][] { { { 3, Opcode.CONST_4 } } } },
                        { 3, Opcode.CONST_4, new Object[][][] { { { 4, Opcode.RETURN_VOID } } } },
                        { 4, Opcode.RETURN_VOID, new Object[1][0][0] },
        };
        //@formatter:on

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "verySimple()V");
        manipulator.removeInstruction(0);

        test(expected, manipulator);
        testHeritage(manipulator, 0);
    }

    @Test
    public void testRemoveInstructionWithParentModifiesStateCorrectly() {
        //@formatter:off
        Object[][] expected = new Object[][] {
                        { 0, Opcode.CONST_4, new Object[][][] { { { 1, Opcode.CONST_4 } } } },
                        { 1, Opcode.CONST_4, new Object[][][] { { { 2, Opcode.CONST_4 } } } },
                        { 2, Opcode.CONST_4, new Object[][][] { { { 3, Opcode.CONST_4 } } } },
                        { 3, Opcode.CONST_4, new Object[][][] { { { 4, Opcode.RETURN_VOID } } } },
                        { 4, Opcode.RETURN_VOID, new Object[1][0][0] },
        };
        //@formatter:on

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "verySimple()V");
        manipulator.removeInstruction(1);

        test(expected, manipulator);
        testHeritage(manipulator, 0);
        testHeritage(manipulator, 1);
        testHeritage(manipulator, 2);

        MethodState parentState = manipulator.getNodePile(0).get(0).getContext().getMethodState();
        assertArrayEquals(new int[] { 0 }, parentState.getRegistersAssigned().toArray());

        MethodState childState = manipulator.getNodePile(1).get(0).getContext().getMethodState();
        assertArrayEquals(new int[] { 2 }, childState.getRegistersAssigned().toArray());

        MethodState grandchildState = manipulator.getNodePile(2).get(0).getContext().getMethodState();
        assertArrayEquals(new int[] { 3 }, grandchildState.getRegistersAssigned().toArray());
    }

    @Test
    public void testReplaceInstructionExecutesNewNodeCorrectly() {
        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "constantPredicate()I");

        BuilderInstruction returnVoid = manipulator.getNodePile(4).get(0).getOp().getInstruction();
        Label target = returnVoid.getLocation().addNewLabel();

        // GOTO_32 shifts addresses around so mappings could break
        BuilderInstruction replacement = new BuilderInstruction30t(Opcode.GOTO_32, target);
        manipulator.replaceInstruction(1, replacement);

        testHeritage(manipulator, 0);
    }

    @Test
    public void testReplacingInstructionWithDifferentOpcodeWidthModifiesStateCorrectly() {
        //@formatter:off
        Object[][] expected = new Object[][] {
                        { 0, Opcode.CONST_16, new Object[][][] { { { 2, Opcode.CONST_4 } } } },
                        { 2, Opcode.CONST_4, new Object[][][] { { { 3, Opcode.CONST_4 } } } },
                        { 3, Opcode.CONST_4, new Object[][][] { { { 4, Opcode.CONST_4 } } } },
                        { 4, Opcode.CONST_4, new Object[][][] { { { 5, Opcode.CONST_4 } } } },
                        { 5, Opcode.CONST_4, new Object[][][] { { { 6, Opcode.RETURN_VOID } } } },
                        { 6, Opcode.RETURN_VOID, new Object[1][0][0] },
        };
        //@formatter:on

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "verySimple()V");
        BuilderInstruction replacement = new BuilderInstruction21s(Opcode.CONST_16, 0, 0);
        manipulator.replaceInstruction(0, replacement);

        test(expected, manipulator);

        HeapItem consensus = manipulator.getRegisterConsensus(0, 0);
        assertEquals(0, consensus.getValue());
    }

    @Test
    public void testReplaceInstructionWithMultipleModifiesStateCorrectly() {
        //@formatter:off
        Object[][] expected = new Object[][] {
                        { 0, Opcode.CONST_4, new Object[][][] { { { 1, Opcode.CONST_16 } } } },
                        { 1, Opcode.CONST_16, new Object[][][] { { { 3, Opcode.CONST_16 } } } },
                        { 3, Opcode.CONST_16, new Object[][][] { { { 5, Opcode.CONST_4 } } } },
                        { 5, Opcode.CONST_4, new Object[][][] { { { 6, Opcode.CONST_4 } } } },
                        { 6, Opcode.CONST_4, new Object[][][] { { { 7, Opcode.CONST_4 } } } },
                        { 7, Opcode.CONST_4, new Object[][][] { { { 8, Opcode.RETURN_VOID } } } },
                        { 8, Opcode.RETURN_VOID, new Object[1][0][0] },
        };
        //@formatter:on

        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "verySimple()V");
        BuilderInstruction replacement1 = new BuilderInstruction21s(Opcode.CONST_16, 1, 1);
        BuilderInstruction replacement2 = new BuilderInstruction21s(Opcode.CONST_16, 2, 2);
        List<BuilderInstruction> replacements = new LinkedList<BuilderInstruction>();
        replacements.add(replacement1);
        replacements.add(replacement2);
        manipulator.replaceInstruction(1, replacements);

        test(expected, manipulator);
        testHeritage(manipulator, 0);
        testHeritage(manipulator, 1);
        testHeritage(manipulator, 3);

        HeapItem consensus;
        consensus = manipulator.getRegisterConsensus(1, 1);
        assertEquals(1, consensus.getValue());

        consensus = manipulator.getRegisterConsensus(3, 2);
        assertEquals(2, consensus.getValue());
    }

    @Test
    public void testReplacingInstructionGetsLabelsAtInsertionAddress() {
        manipulator = OptimizerTester.getGraphManipulator(CLASS_NAME, "hasLabelOnConstantizableOp(I)I");
        BuilderInstruction addition = new BuilderInstruction11n(Opcode.CONST_4, 0, 2);

        assertEquals(1, manipulator.getInstruction(3).getLocation().getLabels().size());
        manipulator.replaceInstruction(3, addition);
        assertEquals(1, manipulator.getInstruction(3).getLocation().getLabels().size());
    }

    private static void test(Object[][] expected, ExecutionGraphManipulator manipulator) {
        for (Object[] ex : expected) {
            int address = (Integer) ex[0];
            BuilderInstruction actualInstruction = manipulator.getInstruction(address);
            Opcode expectedOpcode = (Opcode) ex[1];
            assertEquals(expectedOpcode, actualInstruction.getOpcode());

            Object[][][] exChildren = (Object[][][]) ex[2];
            List<ExecutionNode> actualNodePile = manipulator.getNodePile(address);
            assertEquals(expectedOpcode.name + " @" + address + " node pile size", exChildren.length,
                            actualNodePile.size());
            for (int i = 0; i < exChildren.length; i++) {
                ExecutionNode actualNode = actualNodePile.get(i);
                List<ExecutionNode> childNodes = actualNode.getChildren();
                BuilderInstruction[] children = new BuilderInstruction[childNodes.size()];
                for (int j = 0; j < children.length; j++) {
                    children[j] = childNodes.get(j).getOp().getInstruction();
                }

                assertEquals(expectedOpcode.name + " @" + address + " children size", exChildren[i].length,
                                children.length);
                for (int j = 0; j < exChildren[i].length; j++) {
                    assertEquals(expectedOpcode.name + " @" + address + " child address", (int) exChildren[i][j][0],
                                    children[j].getLocation().getCodeAddress());
                    assertEquals(expectedOpcode.name + " @" + address + " child opcode", exChildren[i][j][1],
                                    children[j].getOpcode());
                }
            }
        }
    }

    private static void testHeritage(ExecutionGraphManipulator manipulator, int address) {
        ExecutionNode template = manipulator.getTemplateNode(address);
        assertEquals(0, template.getChildren().size());
        assertNotNull(template.getOp().getChildren());

        ExecutionNode node = manipulator.getNodePile(address).get(0);
        assertEquals(template.getOp(), node.getOp());

        List<ExecutionNode> children = node.getChildren();
        assertEquals(1, children.size());

        MethodLocation[] childLocations = node.getChildLocations();
        assertEquals(1, childLocations.length);

        ExecutionNode child = node.getChildren().get(0);
        assertEquals(node, child.getParent());
        assertEquals(node.getContext(), child.getContext().getParent());

        Op childOp = child.getOp();
        assertEquals(childOp.getLocation(), childLocations[0]);
        assertEquals(childOp.getLocation(), node.getOp().getChildren()[0]);
    }

}
