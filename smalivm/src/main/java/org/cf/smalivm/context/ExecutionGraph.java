package org.cf.smalivm.context;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.cf.smalivm.SideEffect;
import org.cf.smalivm.VirtualMachine;
import org.cf.smalivm.opcode.Op;
import org.cf.smalivm.opcode.OpCreator;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MethodLocation;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.util.ReferenceUtil;
import org.jf.dexlib2.writer.builder.BuilderMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionGraph implements Iterable<ExecutionNode> {

    private static final Logger log = LoggerFactory.getLogger(ExecutionGraph.class.getSimpleName());

    protected static final int TEMPLATE_NODE_INDEX = 0;
    protected static final int METHOD_ROOT_ADDRESS = 0;

    protected static OpCreator getOpCreator(VirtualMachine vm, TIntObjectMap<MethodLocation> addressToLocation) {
        return new OpCreator(vm, addressToLocation);
    }

    private static Map<MethodLocation, List<ExecutionNode>> buildLocationToNodePile(VirtualMachine vm,
                    TIntObjectMap<MethodLocation> addressToLocation) {
        OpCreator opCreator = getOpCreator(vm, addressToLocation);
        Map<MethodLocation, List<ExecutionNode>> locationToNodePile = new HashMap<MethodLocation, List<ExecutionNode>>();
        for (MethodLocation location : addressToLocation.values(new MethodLocation[addressToLocation.size()])) {
            Op op = opCreator.create(location);
            ExecutionNode node = new ExecutionNode(op);

            // Most node piles will be a template node and 1+ ExecutionNodes.
            List<ExecutionNode> pile = new ArrayList<ExecutionNode>(2);
            pile.add(node);
            locationToNodePile.put(location, pile);
        }

        return locationToNodePile;
    }

    private static TIntList buildTerminatingAddresses(List<BuilderInstruction> instructions) {
        TIntList result = new TIntArrayList();
        for (BuilderInstruction instruction : instructions) {
            int address = instruction.getLocation().getCodeAddress();
            /*
             * Array payload is a weird pseudo instruction. We treat it like a normal one but perhaps a better way would
             * be to make it easier for operations to execute other operations, perhaps looking up by address. This
             * would eliminate the need for MethodState.pseudoInstructionReturnAddress.
             */
            Opcode op = instruction.getOpcode();
            if (op.canContinue() || (op == Opcode.ARRAY_PAYLOAD) || op.name.startsWith("goto")) {
                continue;
            }
            result.add(address);
        }

        return result;
    }

    private final String methodDescriptor;
    private final TIntList terminatingAddresses;
    protected final Map<MethodLocation, List<ExecutionNode>> locationToNodePile;
    protected final TIntObjectMap<MethodLocation> addressToLocation;

    public ExecutionGraph(ExecutionGraph other) {
        methodDescriptor = other.methodDescriptor;
        locationToNodePile = new HashMap<MethodLocation, List<ExecutionNode>>();
        for (MethodLocation location : other.locationToNodePile.keySet()) {
            List<ExecutionNode> otherNodePile = other.locationToNodePile.get(location);
            List<ExecutionNode> nodePile = new ArrayList<ExecutionNode>(otherNodePile.size());
            for (ExecutionNode otherNode : otherNodePile) {
                nodePile.add(new ExecutionNode(otherNode));
            }
            locationToNodePile.put(location, nodePile);
        }
        terminatingAddresses = other.terminatingAddresses;
        addressToLocation = other.addressToLocation;
    }

    public ExecutionGraph(ExecutionGraph other, boolean wrap) {
        locationToNodePile = other.locationToNodePile;
        methodDescriptor = other.methodDescriptor;
        terminatingAddresses = other.terminatingAddresses;
        addressToLocation = other.addressToLocation;
    }

    public ExecutionGraph(VirtualMachine vm, BuilderMethod method) {
        methodDescriptor = ReferenceUtil.getMethodDescriptor(method);
        MutableMethodImplementation implementation = (MutableMethodImplementation) method.getImplementation();
        addressToLocation = buildAddressToLocation(implementation);
        locationToNodePile = buildLocationToNodePile(vm, addressToLocation);
        List<BuilderInstruction> instructions = implementation.getInstructions();
        terminatingAddresses = buildTerminatingAddresses(instructions);
    }

    protected static TIntObjectMap<MethodLocation> buildAddressToLocation(MutableMethodImplementation implementation) {
        List<BuilderInstruction> instructions = implementation.getInstructions();
        TIntObjectMap<MethodLocation> addressToLocation = new TIntObjectHashMap<MethodLocation>(instructions.size());
        for (BuilderInstruction instruction : instructions) {
            MethodLocation location = instruction.getLocation();
            int address = location.getCodeAddress();
            addressToLocation.put(address, location);
        }

        return addressToLocation;
    }

    public void addNode(ExecutionNode node) {
        MethodLocation location = node.getOp().getInstruction().getLocation();
        locationToNodePile.get(location).add(node);
    }

    /**
     * 
     * @return Naturally sorted array of all unique addresses in the graph.
     */
    public int[] getAddresses() {
        int[] addresses = addressToLocation.keys();
        Arrays.sort(addresses);

        return addresses;
    }

    public Collection<MethodLocation> getLocations() {
        return addressToLocation.valueCollection();
    }

    public Set<String> getAllPossiblyInitializedClasses(TIntSet addressSet) {
        Set<String> allClasses = new HashSet<String>();
        for (int address : addressSet.toArray()) {
            List<ExecutionNode> pile = getNodePile(address);
            for (ExecutionNode node : pile) {
                allClasses.addAll(node.getContext().getInitializedClasses());
            }
        }

        return allClasses;
    }

    public TIntSet getConnectedTerminatingAddresses() {
        int maxSize = terminatingAddresses.size();
        TIntSet addresses = new TIntHashSet(maxSize);
        for (int i = 0; i < maxSize; i++) {
            int address = terminatingAddresses.get(i);
            if (wasAddressReached(address)) {
                addresses.add(address);
            }
        }

        return addresses;
    }

    public HeapItem getFieldConsensus(TIntSet addressSet, String fieldDescriptor) {
        String[] parts = fieldDescriptor.split("->");
        String className = parts[0];
        String fieldNameAndType = parts[1];

        return getFieldConsensus(addressSet, className, fieldNameAndType);
    }

    public HeapItem getFieldConsensus(TIntSet addressSet, String className, String fieldNameAndType) {
        String[] parts = fieldNameAndType.split(":");
        String type = parts[1];
        Set<HeapItem> items = new HashSet<HeapItem>();
        for (int address : addressSet.toArray()) {
            // If the class wasn't initialized in one path, it's unknown
            for (ExecutionNode node : getNodePile(address)) {
                if (!node.getContext().isClassInitialized(className)) {
                    return HeapItem.newUnknown(type);
                }
            }

            items.addAll(getFieldItems(address, className, fieldNameAndType));
            if (1 != items.size()) {
                // since set, size == 1 -> consensus
                if (log.isTraceEnabled()) {
                    log.trace("No conensus for " + className + "->" + fieldNameAndType + ", returning unknown");
                }

                return HeapItem.newUnknown(type);
            }
        }

        return items.toArray(new HeapItem[items.size()])[0];
    }

    public Set<HeapItem> getFieldItems(int address, String className, String fieldNameAndType) {
        List<ExecutionNode> nodePile = getNodePile(address);
        Set<HeapItem> items = new HashSet<HeapItem>(nodePile.size());
        for (ExecutionNode node : nodePile) {
            ExecutionContext ectx = node.getContext();
            ClassState cState = ectx.peekClassState(className);
            HeapItem item = cState.peekField(fieldNameAndType);
            items.add(item);
        }

        return items;
    }

    public SideEffect.Level getHighestClassSideEffectLevel(String className) {
        TIntSet addressSet = getConnectedTerminatingAddresses();
        SideEffect.Level result = SideEffect.Level.NONE;
        for (int address : addressSet.toArray()) {
            List<ExecutionNode> pile = getNodePile(address);
            for (ExecutionNode node : pile) {
                SideEffect.Level level = node.getContext().getClassSideEffectLevel(className);
                if (level == null) {
                    // Maybe the class wasn't initialized.
                    continue;
                }
                switch (level) {
                case STRONG:
                    return level;
                case WEAK:
                    result = level;
                    break;
                case NONE:
                    break;
                }
            }
        }

        return result;
    }

    public SideEffect.Level getHighestMethodSideEffectLevel() {
        SideEffect.Level result = SideEffect.Level.NONE;
        for (ExecutionNode node : this) {
            Op op = node.getOp();
            SideEffect.Level level = op.getSideEffectLevel();
            switch (level) {
            case STRONG:
                return level;
            case WEAK:
                result = level;
                break;
            case NONE:
                break;
            }
        }

        return result;
    }

    public SideEffect.Level getHighestSideEffectLevel() {
        SideEffect.Level result = getHighestMethodSideEffectLevel();
        if (result == SideEffect.Level.STRONG) {
            return result;
        }

        TIntSet addressSet = getConnectedTerminatingAddresses();
        Set<String> allClasses = getAllPossiblyInitializedClasses(addressSet);
        for (String className : allClasses) {
            SideEffect.Level level = getHighestClassSideEffectLevel(className);
            switch (level) {
            case STRONG:
                return level;
            case WEAK:
                result = level;
                break;
            case NONE:
                break;
            }
        }

        return result;
    }

    public String getMethodDescriptor() {
        return methodDescriptor;
    }

    public int getNodeCount() {
        int totalSize = locationToNodePile.size();
        int templateCount = locationToNodePile.keySet().size();

        return totalSize - templateCount;
    }

    private @Nullable List<ExecutionNode> getNodePileByAddress(int address) {
        MethodLocation location = addressToLocation.get(address);

        return locationToNodePile.get(location);
    }

    public List<ExecutionNode> getNodePile(int address) {
        List<ExecutionNode> nodePile = getNodePileByAddress(address);
        nodePile = nodePile.subList(1, nodePile.size()); // exclude template

        return nodePile;
    }

    public @Nullable Op getOp(int address) {
        // Node piles share an Op reference
        return getTemplateNode(address).getOp();
    }

    public HeapItem getRegisterConsensus(int address, int register) {
        TIntSet addresses = new TIntHashSet(new int[] { address });

        return getRegisterConsensus(addresses, register);
    }

    public @Nonnull HeapItem getRegisterConsensus(TIntSet addressList, int register) {
        Set<HeapItem> items = new HashSet<HeapItem>();
        for (int address : addressList.toArray()) {
            items.addAll(getRegisterItems(address, register));
            if (items.size() == 0) {
                // TODO: hack for throw not implemented correctly
                continue;
            }

            // Size may be 0 if there was an exception
            if (items.size() != 1) {
                log.trace("No conensus for register #{}, returning unknown", register);
                HeapItem item = items.toArray(new HeapItem[items.size()])[0];

                return HeapItem.newUnknown(item.getType());
            }
        }

        return items.toArray(new HeapItem[1])[0];
    }

    public Object getRegisterConsensusValue(int address, int register) {
        HeapItem item = getRegisterConsensus(address, register);
        if (null == item) {
            return null;
        }

        return item.getValue();
    }

    public @Nullable Object getRegisterConsensusValue(TIntSet addressList, int register) {
        HeapItem item = getRegisterConsensus(addressList, register);
        if (null == item) {
            return null;
        }

        return item.getValue();
    }

    public Set<HeapItem> getRegisterItems(int address, int register) {
        List<ExecutionNode> nodePile = getNodePile(address);
        Set<HeapItem> items = new HashSet<HeapItem>(nodePile.size());
        for (ExecutionNode node : nodePile) {
            MethodState mState = node.getContext().getMethodState();
            HeapItem item = mState.peekRegister(register);
            if (item == null) {
                // If getting terminating register consensus, this may include THROW ops
                // Since they're not implemented, the return value is NULL
                // It's also possible there was an exception during invocation.
                // It could also be return-void
                // Or it could just be caller is getting register consensus of weird address
                // assert node.getExceptions().size() > 0 || node.getOp().getInstruction().getOpcode() ==
                // org.jf.dexlib2.Opcode.THROW;
                // TODO: handle THROW properly
            } else {
                items.add(item);
            }
        }

        return items;
    }

    public ExecutionNode getRoot() {
        List<ExecutionNode> pile = getNodePileByAddress(METHOD_ROOT_ADDRESS);
        // Return node with initialized context if available.
        if (pile.size() > 1) {
            return pile.get(1);
        } else {
            return pile.get(TEMPLATE_NODE_INDEX);
        }
    }

    public @Nullable ExecutionNode getTemplateNode(int address) {
        List<ExecutionNode> nodePile = getNodePileByAddress(address);

        return nodePile.get(TEMPLATE_NODE_INDEX);
    }

    public List<ExecutionContext> getTerminatingContexts() {
        List<ExecutionContext> contexts = new LinkedList<ExecutionContext>();
        TIntSet addresses = getConnectedTerminatingAddresses();
        for (int address : addresses.toArray()) {
            for (ExecutionNode node : getNodePile(address)) {
                contexts.add(node.getContext());
            }
        }

        return contexts;
    }

    protected int getNodeIndex(ExecutionNode node) {
        return getNodePile(node.getAddress()).indexOf(node);
    }

    public HeapItem getTerminatingFieldConsensus(String fieldDescriptor) {
        Map<String, HeapItem> items = getTerminatingFieldConsensus(new String[] { fieldDescriptor });

        return items.get(fieldDescriptor);
    }

    public Map<String, HeapItem> getTerminatingFieldConsensus(String[] fieldDescriptors) {
        TIntSet addresses = getConnectedTerminatingAddresses();
        Map<String, HeapItem> result = new HashMap<String, HeapItem>(fieldDescriptors.length);
        for (String fieldDescriptor : fieldDescriptors) {
            HeapItem item = getFieldConsensus(addresses, fieldDescriptor);
            result.put(fieldDescriptor, item);
        }

        return result;
    }

    public @Nonnull HeapItem getTerminatingRegisterConsensus(int register) {
        Map<Integer, HeapItem> items = getTerminatingRegisterConsensus(new int[] { register });

        return items.get(register);
    }

    public Map<Integer, HeapItem> getTerminatingRegisterConsensus(int[] registers) {
        TIntSet addresses = getConnectedTerminatingAddresses();
        Map<Integer, HeapItem> result = new HashMap<Integer, HeapItem>(registers.length);
        for (int register : registers) {
            HeapItem item = getRegisterConsensus(addresses, register);
            result.put(register, item);
        }

        return result;
    }

    @Override
    public Iterator<ExecutionNode> iterator() {
        return new ExecutionGraphIterator(this);
    }

    public boolean wasAddressReached(int address) {
        if (METHOD_ROOT_ADDRESS == address) {
            // Root is always reachable
            return true;
        }

        // If this address was reached during execution there will be clones in the pile.
        List<ExecutionNode> nodePile = getNodePileByAddress(address);
        if ((nodePile == null) || (1 > nodePile.size())) {
            log.warn("Node pile @" + address + " has no template node.");
            return false;
        }

        return nodePile.size() > 1;
    }

}
