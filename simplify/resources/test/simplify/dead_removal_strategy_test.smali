.class Ldead_removal_strategy_test;
.super Ljava/lang/Object;

.method public constructor <init>()V
  .locals 1

  invoke-direct {p0}, Ljava/lang/Object;-><init>()V

  return-void
.end method

.method public static UnusedAssignment()I
  .locals 1

  const/4 v0, 0x1
  const/4 v0, 0x2

  return v0
.end method

.method public static DeadCode()V
  .locals 1

  const/4 v0, 0x1

  goto :return

  add-int/2addr v0, v0
  nop
  nop
  nop

  :return
  return-void
.end method

.method public static DeadCodeWithStrongSideEffect()V
  .locals 1

  goto :return

  invoke-static {}, Lsome/classy/Clazz;->important()V

  :return
  return-void
.end method

.method public static DeadOpWithLabel()I
  .locals 2

  const/4 v0, 0x1
  if-gtz v0, :return

  const/4 v0, 0x1

  :return
  const/4 v1, 0x1

  return v0
.end method

.method public static ReassignedInOnlyOneMultiverse(I)I
  .locals 2

  const/4 v0, 0x1

  const/4 v1, 0x0
  if-ge p0, v1, :return

  const/4 v0, 0x0

  :return
  return v0
.end method

.method public static UselessGoto()V
  .locals 0

  goto :return

  :return
  return-void
.end method

.method public static DeadTryCatchBlock()V
  .locals 1

  :try_start_1
  const/4 v0, 0x2

  :try_end_1
  .catch Ljava/lang/Exception; {:try_start_1 .. :try_end_1} :catch_1

  :catch_1
  :end
  return-void
.end method

.method public static UnusedResultNoSideEffects()I
  .locals 1

  const/4 v0, 0x2
  invoke-static {v0}, Ldead_removal_strategy_test;->AddOneNoSideEffects(I)I

  return v0
.end method

.method public static UnusedResultWithSideEffects()I
  .locals 1

  const/4 v0, 0x2
  invoke-static {v0}, Ldead_removal_strategy_test;->AddOneWithSideEffects(I)I

  return v0
.end method

.method private static AddOneNoSideEffects(I)I
  .locals 0

  add-int/2addr p0, p0

  return p0
.end method

.method private static AddOneWithSideEffects(I)I
  .locals 0

  invoke-static {}, Lunknown_class;->UnknownMethodHasSideEffects()V
  add-int/2addr p0, p0

  return p0
.end method

.method public static MoveP0IntoV0With30Locals(I)V
    .locals 30

    # invoke-static needs 4 bit registers, so gotta move from p0 to v0
    move-object/from16 v0, p0
    invoke-static {v0}, Lsome/silly/class;->NotAMethod(I)V

    # the crux. optimizer must see p0 is not used, but know v0 is, so don't remove the move-object/from16.
    move-object/from16 p0, v0

    return-object p0
.end method

.method public static AppendV0ToV1StringBuilderAndReturnResult()V
  .locals 5

  invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
  move-result-object v1

  return-object v1
.end method

.method private static hasNopPadding()V
    .locals 1

    #@0
    const/16 v0, 0x1

    #@2
    new-array v0, v0, [B

    #@4
    fill-array-data v0, :array_0

    #@7
    # if this is removed, nop @b is not needed and is removed by dexlib
    fill-array-data v0, :array_0

    #@a
    return-void

    #@b
    nop

    #@c
    :array_0
    .array-data 1
        0x1t
    .end array-data
.end method
