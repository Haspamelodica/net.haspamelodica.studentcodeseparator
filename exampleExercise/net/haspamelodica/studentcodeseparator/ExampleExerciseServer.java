package net.haspamelodica.studentcodeseparator;

import static net.haspamelodica.studentcodeseparator.LoggingUtils.maybeWrapLogging;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import net.haspamelodica.studentcodeseparator.communicator.impl.data.student.DataCommunicatorServer;
import net.haspamelodica.studentcodeseparator.communicator.impl.samejvm.DirectSameJVMCommunicatorWithoutSerialization;

public class ExampleExerciseServer
{
	public static final int PORT = 1337;

	public static void main(String[] args) throws IOException
	{
		try(ServerSocket serverSocket = new ServerSocket(PORT);
				Socket sock = serverSocket.accept();
				DataInputStream in = new DataInputStream(sock.getInputStream());
				DataOutputStream out = new DataOutputStream(sock.getOutputStream()))
		{
			DataCommunicatorServer server = new DataCommunicatorServer(in, out,
					refManager -> maybeWrapLogging(new DirectSameJVMCommunicatorWithoutSerialization<>(refManager)));
			server.run();
		}
	}
}
