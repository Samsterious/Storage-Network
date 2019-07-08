package mrriegel.storagenetwork.capabilities;
import mrriegel.storagenetwork.api.capability.IConnectable;
import mrriegel.storagenetwork.api.capability.IConnectableLink;
import mrriegel.storagenetwork.api.data.DimPos;
import mrriegel.storagenetwork.api.data.EnumStorageDirection;
import mrriegel.storagenetwork.api.data.IItemStackMatcher;
import mrriegel.storagenetwork.util.inventory.FilterItemStackHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

// TODO: We should add support for CommonCapabilities SlotlessItemHandler for efficiency reasons and compatibility with colossal chests, integrated dynamics etc
public class CapabilityConnectableLink implements IConnectableLink, INBTSerializable<CompoundNBT> {

  private final IConnectable connectable;
  private boolean operationMustBeSmaller = true;
  private ItemStack operationStack = ItemStack.EMPTY;
  private int operationLimit = 0;
  private final FilterItemStackHandler filters = new FilterItemStackHandler();
  private EnumStorageDirection filterDirection = EnumStorageDirection.BOTH;
  private Direction inventoryFace;
  private int priority;

  CapabilityConnectableLink() {
    connectable = new CapabilityConnectable();
    filters.setIsWhitelist(false);
  }

  public CapabilityConnectableLink(TileEntity tile) {
    connectable = tile.getCapability(StorageNetworkCapabilities.CONNECTABLE_CAPABILITY, null).orElse(null);
    filters.setIsWhitelist(false);
  }

  @Override
  public int getPriority() {
    return priority;
  }

  @Override
  public List<ItemStack> getStoredStacks() {
    if (inventoryFace == null) {
      return Collections.EMPTY_LIST;
    }
    DimPos inventoryPos = connectable.getPos().offset(inventoryFace);
    // Test whether the connected block has the IItemHandler capability
    IItemHandler itemHandler = inventoryPos.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, inventoryFace.getOpposite());
    if (itemHandler == null) {
      return Collections.emptyList();
    }
    // If it does, iterate its stacks, filter them and add them to the result list
    List<ItemStack> result = new ArrayList<>();
    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
      ItemStack stack = itemHandler.getStackInSlot(slot);
      if (stack == null || stack.isEmpty()) {
        continue;
      }
      if (filters.isStackFiltered(stack)) {
        continue;
      }
      result.add(stack.copy());
    }
    return result;
  }

  @Override
  public ItemStack insertStack(ItemStack stack, boolean simulate) {
    // If this storage is configured to only import into the network, do not
    // insert into the storage, but abort immediately.
    if (filterDirection == EnumStorageDirection.IN) {
      return stack;
    }
    if (filters.isStackFiltered(stack)) {
      return stack;
    }
    if (inventoryFace == null) {
      return stack;
    }
    DimPos inventoryPos = connectable.getPos().offset(inventoryFace);
    // Test whether the connected block has the IItemHandler capability
    IItemHandler itemHandler = inventoryPos.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, inventoryFace.getOpposite());
    if (itemHandler == null) {
      return stack;
    }
    return ItemHandlerHelper.insertItemStacked(itemHandler, stack, simulate);
  }

  @Override
  public ItemStack extractStack(IItemStackMatcher matcher, int size, boolean simulate) {
    // If nothing is actually being requested, abort immediately
    if (size <= 0) {
      return ItemStack.EMPTY;
    }
    // If this storage is configured to only export from the network, do not
    // extract from the storage, but abort immediately.
    if (filterDirection == EnumStorageDirection.IN) {
      return ItemStack.EMPTY;
    }
    if (inventoryFace == null) {
      return ItemStack.EMPTY;
    }
    DimPos inventoryPos = connectable.getPos().offset(inventoryFace);
    // Test whether the connected block has the IItemHandler capability
    IItemHandler itemHandler = inventoryPos.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, inventoryFace.getOpposite());
    if (itemHandler == null) {
      return ItemStack.EMPTY;
    }
    ItemStack firstMatchedStack = ItemStack.EMPTY;
    int remaining = size;
    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
      //force simulate: allow them to not let me see the stack, also dont extract since it might steal/dupe
      ItemStack stack = itemHandler.extractItem(slot, remaining, true);//itemHandler.getStackInSlot(slot);
      if (stack == null || stack.isEmpty()) {
        continue;
      }
      // Ignore stacks that are filtered
      if (filters.isStackFiltered(stack)) {
        continue;
      }
      // If its not even the item type we're looking for -> continue
      if (firstMatchedStack.isEmpty()) {
        if (!matcher.match(stack)) {
          continue;
        }
        firstMatchedStack = stack.copy();
      }
      else {
        if (!ItemHandlerHelper.canItemStacksStack(firstMatchedStack, stack)) {
          continue;
        }
      }
      int toExtract = Math.min(stack.getCount(), remaining);
      ItemStack extractedStack = itemHandler.extractItem(slot, toExtract, simulate);
      remaining -= extractedStack.getCount();
      if (remaining <= 0) {
        break;
      }
    }
    int extractCount = size - remaining;
    if (!firstMatchedStack.isEmpty() && extractCount > 0) {
      firstMatchedStack.setCount(extractCount);
    }
    return firstMatchedStack;
  }

  @Override
  public int getEmptySlots() {
    // If this storage is configured to only import into the network, do not
    // insert into the storage, but abort immediately.
    if (filterDirection == EnumStorageDirection.IN) {
      return 0;
    }
    if (inventoryFace == null) {
      return 0;
    }
    DimPos inventoryPos = connectable.getPos().offset(inventoryFace);
    // Test whether the connected block has the IItemHandler capability
    IItemHandler itemHandler = inventoryPos.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, inventoryFace.getOpposite());
    if (itemHandler == null) {
      return 0;
    }
    int emptySlots = 0;
    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
      ItemStack stack = itemHandler.getStackInSlot(slot);
      if (stack != null && !stack.isEmpty()) {
        continue;
      }
      emptySlots++;
    }
    return emptySlots;
  }

  @Override
  public EnumStorageDirection getSupportedTransferDirection() {
    return filterDirection;
  }

  public void setInventoryFace(Direction inventoryFace) {
    this.inventoryFace = inventoryFace;
  }

  @Override
  public CompoundNBT serializeNBT() {
    CompoundNBT result = new CompoundNBT();
    CompoundNBT filters = this.filters.serializeNBT();
    result.put("filters", filters);
    result.putInt("prio", priority);
    if (inventoryFace != null) {
      result.putString("inventoryFace", inventoryFace.toString());
    }
    result.putString("way", filterDirection.toString());
    CompoundNBT operation = new CompoundNBT();
    operation.put("stack", operationStack.serializeNBT());
    operation.putBoolean("mustBeSmaller", operationMustBeSmaller);
    operation.putInt("limit", operationLimit);
    result.put("operation", operation);
    return result;
  }

  @Override
  public void deserializeNBT(CompoundNBT nbt) {
    CompoundNBT filters = nbt.getCompound("filters");
    this.filters.deserializeNBT(filters);
    priority = nbt.getInt("prio");
    if (nbt.contains("inventoryFace")) {
      inventoryFace = Direction.byName(nbt.getString("inventoryFace"));
    }
    try {
      filterDirection = EnumStorageDirection.valueOf(nbt.getString("way"));
    }
    catch (Exception e) {
      filterDirection = EnumStorageDirection.BOTH;
    }
    CompoundNBT operation = nbt.getCompound("operation");
    operationLimit = operation.getInt("limit");
    operationMustBeSmaller = operation.getBoolean("mustBeSmaller");
    if (operation.contains("stack", Constants.NBT.TAG_COMPOUND)) {
      operationStack = ItemStack.read(operation.getCompound("stack"));
    }
    else {
      operationStack = ItemStack.EMPTY;
    }
  }

  public static class Factory implements Callable<IConnectableLink> {

    @Override
    public IConnectableLink call() throws Exception {
      return new CapabilityConnectableLink();
    }
  }

  public static class Storage implements Capability.IStorage<IConnectableLink> {

    @Nullable
    @Override
    public INBT writeNBT(Capability<IConnectableLink> capability, IConnectableLink rawInstance, Direction side) {
      CapabilityConnectableLink instance = (CapabilityConnectableLink) rawInstance;
      return instance.serializeNBT();
    }

    @Override public void readNBT(Capability<IConnectableLink> capability, IConnectableLink rawInstance, Direction side, INBT nbt) {
      CapabilityConnectableLink instance = (CapabilityConnectableLink) rawInstance;
      instance.deserializeNBT((CompoundNBT) nbt);
    }
  }
}
