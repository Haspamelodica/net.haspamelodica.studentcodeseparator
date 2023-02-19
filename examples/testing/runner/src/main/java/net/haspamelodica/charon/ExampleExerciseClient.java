package net.haspamelodica.charon;

import static net.haspamelodica.charon.ExampleExercise.run;
import static net.haspamelodica.charon.communicator.impl.LoggingCommunicatorClientSide.maybeWrapLoggingC;
import static net.haspamelodica.charon.communicator.impl.LoggingCommunicatorServerSide.maybeWrapLoggingS;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.Semaphore;

import net.haspamelodica.charon.communicator.StudentSideCommunicatorServerSide;
import net.haspamelodica.charon.communicator.impl.RefTranslatorCommunicatorServerSide;
import net.haspamelodica.charon.communicator.impl.data.exercise.DataCommunicatorClient;
import net.haspamelodica.charon.communicator.impl.data.student.DataCommunicatorServer;
import net.haspamelodica.charon.communicator.impl.samejvm.DirectSameJVMCommunicatorClientSide;
import net.haspamelodica.charon.communicator.impl.samejvm.DirectSameJVMCommunicatorServerSide;
import net.haspamelodica.charon.impl.StudentSideImpl;
import net.haspamelodica.charon.refs.longref.SimpleLongRefManager.LongRef;

public class ExampleExerciseClient
{
	private static final boolean LOGGING = false;
	// If you use DATA_OTHER_JVM, start ExampleExerciseServer first.
	private static final Mode MODE = Mode.DATA_OTHER_JVM;
	// HOST and PORT only matter for mode DATA_OTHER_JVM
	private static final String	HOST	= "localhost";
	private static final int	PORT	= 1337;

	private enum Mode
	{
		DIRECT, DATA_SAME_JVM, DATA_OTHER_JVM;
	}

	public static void main(String[] args) throws IOException, InterruptedException
	{
		// An instance of StudentSide will at some point be provided by the framework, not created by the exercise.
		// Also, Ares would not use a DirectSameJVMCommunicator (or DataCommunicatorServer) in the exercise JVM.
		switch(MODE)
		{
			case DIRECT -> runDirect();
			case DATA_SAME_JVM -> runDataSameJVM();
			case DATA_OTHER_JVM -> runDataOtherJVM();
		}
	}

	// --- Code below here will be moved to the framework.

	private static void runDirect()
	{
		run(new StudentSideImpl<>(maybeWrapLoggingC(new DirectSameJVMCommunicatorClientSide(), LOGGING)));
	}

	private static void runDataSameJVM() throws InterruptedException, IOException
	{
		try(PipedInputStream clientIn = new PipedInputStream(); PipedOutputStream clientOut = new PipedOutputStream())
		{
			Semaphore serverConnected = new Semaphore(0);
			new Thread(() ->
			{
				try(PipedInputStream serverIn = new PipedInputStream(clientOut); PipedOutputStream serverOut = new PipedOutputStream(clientIn))
				{
					serverConnected.release();
					StudentSideCommunicatorServerSide<Object> directComm = new DirectSameJVMCommunicatorServerSide();
					StudentSideCommunicatorServerSide<LongRef> translatedComm = new RefTranslatorCommunicatorServerSide<>(directComm, false);
					StudentSideCommunicatorServerSide<LongRef> loggingComm = maybeWrapLoggingS(translatedComm, "SERVER: ", LOGGING);
					DataCommunicatorServer server = new DataCommunicatorServer(serverIn, serverOut, loggingComm);
					server.run();
				} catch(IOException e)
				{
					throw new UncheckedIOException(e);
				} finally
				{
					// Might release twice; doesn't matter
					serverConnected.release();
				}
			}).start();
			// wait for the server to create PipedOutputStreams
			serverConnected.acquire();
			DataCommunicatorClient client = new DataCommunicatorClient(clientIn, clientOut);
			run(new StudentSideImpl<>(maybeWrapLoggingC(client, "CLIENT: ", LOGGING)));
			client.shutdown();
		}
	}

	private static void runDataOtherJVM() throws IOException, UnknownHostException
	{
		try(Socket sock = new Socket(HOST, PORT))
		{
			DataCommunicatorClient client = new DataCommunicatorClient(
					sock.getInputStream(), sock.getOutputStream());
			try
			{
				run(new StudentSideImpl<>(maybeWrapLoggingC(client, LOGGING)));
			} finally
			{
				client.shutdown();
			}
		}
	}
}
