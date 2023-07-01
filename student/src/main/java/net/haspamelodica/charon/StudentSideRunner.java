package net.haspamelodica.charon;

import static net.haspamelodica.charon.communicator.CommunicatorUtils.wrapTypeCaching;
import static net.haspamelodica.charon.communicator.ServerSideCommunicatorUtils.createDirectCommServer;
import static net.haspamelodica.charon.communicator.ServerSideCommunicatorUtils.maybeWrapLoggingExtServer;
import static net.haspamelodica.charon.communicator.ServerSideCommunicatorUtils.wrapReftransExtServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.haspamelodica.charon.communicator.impl.data.student.DataCommunicatorServer;
import net.haspamelodica.charon.communicator.impl.logging.CommunicationLoggerParams;
import net.haspamelodica.charon.utils.communication.Communication;
import net.haspamelodica.charon.utils.communication.CommunicationArgsParser;
import net.haspamelodica.charon.utils.communication.IncorrectUsageException;

public class StudentSideRunner
{
	public static void main(String[] args) throws IOException, InterruptedException
	{
		try(Communication communication = Communication.open(CommunicationArgsParser.parse(args)))
		{
			//TODO maybe make classloader configurable?
			run(communication, null);
		} catch(IncorrectUsageException e)
		{
			e.printStackTrace();
			System.err.println("Usage: java " + StudentSideRunner.class.getName() + CommunicationArgsParser.argsSyntax());
		}
	}

	public static void run(Communication communication, ClassLoader studentClassesClassloader) throws IOException
	{
		run(communication.getIn(), communication.getOut(), communication.getLogging(), studentClassesClassloader);
	}

	public static void run(InputStream in, OutputStream out, boolean logging, ClassLoader studentClassesClassloader) throws IOException
	{
		DataCommunicatorServer server = new DataCommunicatorServer(in, out,
				wrapTypeCaching(
						// We don't need ALL_TO_STRING, because we are on the server side:
						// The only situation where an object is logged is in ClientSideTransceiver.
						maybeWrapLoggingExtServer(logging, CommunicationLoggerParams.DEFAULT_REF_TO_STRING,
								wrapReftransExtServer(
										createDirectCommServer(studentClassesClassloader)))));
		server.run();
	}
}
