package org.ggp.base.util.statemachine.sancho;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.ggp.base.util.propnet.sancho.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.sancho.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.sancho.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.propnet.sancho.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.sancho.PolymorphicComponent;
import org.ggp.base.util.propnet.sancho.PolymorphicProposition;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

/**
 * Class representing a factor within a game's propnet.  A factor is a partition within a partitioning of the base
 * propositions into disjoint sets between which there are no causative logical connections or coupling via
 * terminal/goal conditions.
 *
 * The pattern for Factor use is...
 *
 * - Create a factor using this method.
 * - Add components and legal moves using the addAll() methods.
 * - When finished constructing, mark the factor as complete using complete().
 *
 */
public class Factor implements StateMachineFilter
{
//  private static final Logger LOGGER = LogManager.getLogger();

  private static final ForwardDeadReckonLegalMoveInfo PSEUDO_NO_OP = new ForwardDeadReckonLegalMoveInfo(true);

  /*
   * When adding state variables to a factor, consider how to save to file (see toPersistentString) and load again
   * (see Factor(String, ...)).
   */
  private Set<PolymorphicComponent>                   mComponents = new HashSet<>();
  private Set<ForwardDeadReckonLegalMoveInfo>         mMoveInfos = new HashSet<>();

  private Set<Move>                                   mMoves = null;
  private ForwardDeadReckonInternalMachineState       mStateMask = null;
  private ForwardDeadReckonInternalMachineState       mFactorSpecificStateMask = null;
  private ForwardDeadReckonInternalMachineState       mInverseStateMask = null;
  private ForwardDeadReckonInternalMachineState       mInverseFactorSpecificStateMask = null;
  private boolean                                     mAlwaysIncludePseudoNoop = false;
  /*
   * When adding state variables to a factor, consider how to save to file (see toPersistentString) and load again
   * (see Factor(String, ...)).
   */

  /**
   * Default constructor used during factor analysis.
   *
   * See class header for usage pattern.
   */
  public Factor()
  {
    // Do nothing.
  }

  /**
   * Create a factor from a saved string, generated by calling toPersistentString.
   *
   * Having loaded all factors and set up info.factor for each proposition, it is still necessary to call complete().
   *
   * @param xiSaved - the saved string.
   * @param xiPropositionInfo - information about all propositions.
   * @param xiMoveInfo - information about all legal moves.
   */
  public Factor(String xiSaved,
                ForwardDeadReckonPropositionCrossReferenceInfo[] xiPropositionInfo,
                ForwardDeadReckonLegalMoveInfo[] xiMoveInfo)
  {
    // Parse the saved string into propositions and legal moves.
    assert(xiSaved.startsWith("v1~")) : "Not a v1 factor";
    xiSaved = xiSaved.substring(3);
    String[] lParts = xiSaved.split("~");
    assert(lParts.length == 2) : "Unexpected number of colons in factor string: " + (lParts.length - 1);

    String[] lProps = lParts[0].split(",");
    SET_PROPS: for (String lProp : lProps)
    {
      // Find the matching prop in the info set.
      for (ForwardDeadReckonPropositionCrossReferenceInfo lInfo : xiPropositionInfo)
      {
        if (lInfo.fullNetProp.getName().toString().equals(lProp))
        {
          mComponents.add(lInfo.fullNetProp);
          continue SET_PROPS;
        }
      }
      assert(false) : "Failed to find proposition: " + lProp;
    }

    SET_MOVES: for (String lMove : lParts[1].split(","))
    {
      for (ForwardDeadReckonLegalMoveInfo lInfo : xiMoveInfo)
      {
        if (lInfo.toPersistentString().equals(lMove))
        {
          mMoveInfos.add(lInfo);
          continue SET_MOVES;
        }
      }
      assert(false) : "failed to find move: " + lMove;
    }
  }

  /**
   * Add the specified components to the factor.
   *
   * @param toAdd - the components to add.
   */
  public void addAll(Collection<? extends PolymorphicComponent> toAdd)
  {
    mComponents.addAll(toAdd);
  }

  /**
   * Add the specified moves to the factor.
   *
   * @param toAdd - the moves to add.
   */
  public void addAllMoves(Collection<ForwardDeadReckonLegalMoveInfo> toAdd)
  {
    mMoveInfos.addAll(toAdd);
  }

  /**
   * @return the components that make up this factor.
   */
  public Set<PolymorphicComponent> getComponents()
  {
    return mComponents;
  }

  /**
   * @return whether the factor contains any of the specified components.
   *
   * @param toTest - the components to search for.
   */
  public boolean containsAny(Collection<? extends PolymorphicComponent> toTest)
  {
    for (PolymorphicComponent c : toTest)
    {
      if (mComponents.contains(c))
      {
        return true;
      }
    }

    return false;
  }

  //--------------------------------------------------------------------------------------------------------------------
  // Methods above this point can only be called before calling complete().
  //--------------------------------------------------------------------------------------------------------------------

  /**
   * Mark the factor complete.  No propositions or moves can be added to it after this call.
   *
   * @param xiStateMachine - a state machine, used to create the state masks.
   */
  public void complete(ForwardDeadReckonPropnetRuleEngine xiStateMachine)
  {
    setUpStateMasks(xiStateMachine);
    cacheMoves();
  }

  /**
   * Set up state masks for the factor state.
   */
  private void setUpStateMasks(ForwardDeadReckonPropnetRuleEngine xiStateMachine)
  {
    assert(mFactorSpecificStateMask == null) : "Factor-specific state mask already set up";
    assert(mInverseFactorSpecificStateMask == null) : "Inverse factor-specific state mask already set up";
    assert(mStateMask == null) : "State mask already set up";
    assert(mInverseStateMask == null) : "Inverse state mask already set up";

    mFactorSpecificStateMask = xiStateMachine.createEmptyInternalState();
    mStateMask = xiStateMachine.createEmptyInternalState();

    for (PolymorphicProposition p : xiStateMachine.getFullPropNet().getBasePropositions().values())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
      ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

      if (info.factor == this)
      {
        mStateMask.add(info);
        mFactorSpecificStateMask.add(info);
      }
      else if (info.factor == null)
      {
        mStateMask.add(info);
      }
    }

    mInverseFactorSpecificStateMask = new ForwardDeadReckonInternalMachineState(mFactorSpecificStateMask);
    mInverseFactorSpecificStateMask.invert();

    mInverseStateMask = new ForwardDeadReckonInternalMachineState(mStateMask);
    mInverseStateMask.invert();
  }

  private void cacheMoves()
  {
    assert(mMoves == null) : "Moves already cached";

    mMoves = new HashSet<>();

    for (ForwardDeadReckonLegalMoveInfo moveInfo : mMoveInfos)
    {
      mMoves.add(moveInfo.mMove);
    }
  }

  /**
   * Make debug logs to identify the contents of this factor.
   */
  public void dump()
  {
//    LOGGER.debug("Factor base props:");
    for (PolymorphicComponent c : mComponents)
    {
      if (c instanceof PolymorphicProposition)
      {
        PolymorphicProposition p = (PolymorphicProposition)c;

//        LOGGER.debug("  " + p.getName());
      }
    }

//    LOGGER.debug("Factor moves:");
    for (ForwardDeadReckonLegalMoveInfo moveInfo : mMoveInfos)
    {
//      LOGGER.debug("  " + moveInfo.mMove);
    }
  }

  //--------------------------------------------------------------------------------------------------------------------
  // Methods below this point can only be called having called complete().
  //--------------------------------------------------------------------------------------------------------------------

  /**
   * @return the move infos for moves that are part of this factor.
   */
  public Set<ForwardDeadReckonLegalMoveInfo> getMoveInfos()
  {
    return mMoveInfos;
  }

  /**
   * @return the moves that are part of this factor.
   */
  public Set<Move> getMoves()
  {
    return mMoves;
  }

  /**
   * @return a state mask for this factor.
   *
   * @param xiStateSpecificOnly - true to exclude state this is a part of all factors, false to include this state.
   */
  public ForwardDeadReckonInternalMachineState getStateMask(boolean xiStateSpecificOnly)
  {
    return (xiStateSpecificOnly ? mFactorSpecificStateMask : mStateMask);
  }

  /**
   * @return the inverse of getStateMask().
   *
   * @param xiStateSpecificOnly - see getStateMask().
   */
  public ForwardDeadReckonInternalMachineState getInverseStateMask(boolean xiStateSpecificOnly)
  {
    return (xiStateSpecificOnly ? mInverseFactorSpecificStateMask : mInverseStateMask);
  }

  /**
   * Set whether a pseudo-noop should always be included in the list of legal moves.
   *
   * @param xiInclude - whether to always include the no-op.
   */
  public void setAlwaysIncludePseudoNoop(boolean xiInclude)
  {
    mAlwaysIncludePseudoNoop = xiInclude;
  }

  @Override
  public boolean isFilteredTerminal(ForwardDeadReckonInternalMachineState xiState,
                                    ForwardDeadReckonPropnetRuleEngine xiStateMachine)
  {
    return xiStateMachine.isTerminal(xiState);
  }

  @Override
  public int getFilteredMovesSize(ForwardDeadReckonInternalMachineState xiState,
                                  ForwardDeadReckonLegalMoveSet xiMoves,
                                  Role role,
                                  boolean xiIncludeForcedPseudoNoops)
  {
    return getFilteredMovesSize(xiMoves.getContents(role), xiIncludeForcedPseudoNoops);
  }

  @Override
  public int getFilteredMovesSize(ForwardDeadReckonInternalMachineState xiState,
                                  ForwardDeadReckonLegalMoveSet xiMoves,
                                  int roleIndex,
                                  boolean xiIncludeForcedPseudoNoops)
  {
    return getFilteredMovesSize(xiMoves.getContents(roleIndex), xiIncludeForcedPseudoNoops);
  }

  /**
   * @return the size of the filtered move set.
   *
   * @param xiMoves - the moves to filter.
   * @param xiIncludeForcedPseudoNoops - whether to include a pseudo-noop.
   */
  private int getFilteredMovesSize(Collection<ForwardDeadReckonLegalMoveInfo> xiMoves,
                                   boolean xiIncludeForcedPseudoNoops)
  {
    int lCount = 0;
    boolean noopFound = false;
    for (ForwardDeadReckonLegalMoveInfo lMove : xiMoves)
    {
      if (lMove.mFactor == null || lMove.mFactor == this)
      {
        lCount++;

        if (lMove.mInputProposition == null || lMove.mFactor == null)
        {
          noopFound = true;
        }
      }
    }

    if (lCount == 0 || (xiIncludeForcedPseudoNoops && !noopFound && mAlwaysIncludePseudoNoop))
    {
      lCount++;
    }

    return lCount;
  }

  @Override
  public ForwardDeadReckonLegalMoveInfo nextFilteredMove(Iterator<ForwardDeadReckonLegalMoveInfo> xiItr)
  {
    ForwardDeadReckonLegalMoveInfo result;

    while(xiItr.hasNext())
    {
      result = xiItr.next();
      if (result.mFactor == this || result.mFactor == null)
      {
        return result;
      }
    }

    // The extra move must be a forced noop
    return PSEUDO_NO_OP;
  }

  /**
   * @return a representation of this factor suitable for persisting to disk and reloading in another session.
   */
  public String toPersistentString()
  {
    // Return the factor-specific state for saving to file.  Do NOT change this representation without considering the
    // implications on loading from disk.

    // Just return the base props and the moves that are part of this factor.  Everything else can be reconstructed, by
    // calling complete().
    StringBuilder lResult = new StringBuilder("v1~");

    // Add the propositions (comma-separated).
    for (PolymorphicComponent c : mComponents)
    {
      if (c instanceof PolymorphicProposition)
      {
        PolymorphicProposition p = (PolymorphicProposition)c;
        lResult.append(p.getName());
        lResult.append(',');
      }
    }

    // Delete the trailing comma and replace with a tilde to separate propositions from moves.
    lResult.setCharAt(lResult.length() - 1, '~');

    // Add the legal moves (comma-separated).
    for (ForwardDeadReckonLegalMoveInfo lMoveInfo : mMoveInfos)
    {
      lResult.append(lMoveInfo.toPersistentString());
      lResult.append(',');
    }

    // Delete the trailing comma.
    lResult.setLength(lResult.length() - 1);

    return lResult.toString();
  }
}