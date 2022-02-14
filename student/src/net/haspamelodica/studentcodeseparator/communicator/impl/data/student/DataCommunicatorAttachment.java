package net.haspamelodica.studentcodeseparator.communicator.impl.data.student;

public class DataCommunicatorAttachment
{
	private final int	id;
	/** 0 means inactive, a positive value means that number of pending sends, a negative value is illegal state */
	private int			pendingSendsCount;

	/** {@link DataCommunicatorAttachment#pendingSendsCount} starts at 1. */
	public DataCommunicatorAttachment(int id)
	{
		this.id = id;
		this.pendingSendsCount = 1;
	}

	public int id()
	{
		return id;
	}

	/**
	 * If this attachment is <i>deactivated</i>, does nothing and returns <code>false</code>.
	 * Otherwise, increments {@link DataCommunicatorAttachment#pendingSendsCount} by 1 and returns <code>true</code>.
	 * See {@link DataCommunicatorAttachment#decreasePendingSendsCount(int)} for details about deactivation.
	 * 
	 * @return <code>true</code> if this attachment is sill active, otherwise <code>false</code>
	 */
	public boolean incrementPendingSendsCount()
	{
		if(pendingSendsCount == 0)
			return false;

		pendingSendsCount ++;
		return true;
	}
	/**
	 * If this attachment is <i>deactivated</i>, throws an exception.
	 * Otherwise, decreases {@link DataCommunicatorAttachment#pendingSendsCount} by the passed argument,
	 * and if this decrement causes the peding sends count to become 0, deactivates this attachment.
	 * 
	 * @return <code>true</code> if this attachment is deactivated (after decrementing), otherwise <code>false</code>
	 */
	public boolean decreasePendingSendsCount(int receivedCount)
	{
		if(pendingSendsCount == 0)
			//TODO better exception type
			throw new IllegalStateException("Attachment already deactivated.");

		pendingSendsCount -= receivedCount;
		if(pendingSendsCount < 0)
			//TODO better exception type
			throw new IllegalStateException("Less pending sends than received.");

		return pendingSendsCount == 0;
	}
}
