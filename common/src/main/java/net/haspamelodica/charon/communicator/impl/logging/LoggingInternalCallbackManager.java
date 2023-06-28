package net.haspamelodica.charon.communicator.impl.logging;

import net.haspamelodica.charon.communicator.InternalCallbackManager;

public class LoggingInternalCallbackManager<REF> implements InternalCallbackManager<REF>
{
	private final CommunicationLogger<?, ?, ?, ?, ?>	logger;
	private final InternalCallbackManager<REF>			callbackManager;

	public LoggingInternalCallbackManager(CommunicationLogger<?, ?, ?, ?, ?> logger, InternalCallbackManager<REF> callbackManager)
	{
		this.logger = logger;
		this.callbackManager = callbackManager;
	}

	@Override
	public REF createCallbackInstance(String interfaceCn)
	{
		logger.logEnter("new callback " + interfaceCn);
		REF result = callbackManager.createCallbackInstance(interfaceCn);
		logger.logExit(result);
		return result;
	}
}
