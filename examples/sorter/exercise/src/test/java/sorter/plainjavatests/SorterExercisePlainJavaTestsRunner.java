package sorter.plainjavatests;

import java.io.IOException;

import net.haspamelodica.studentcodeseparator.WrappedStudentSide;
import net.haspamelodica.studentcodeseparator.utils.communication.CommunicationArgsParser;
import net.haspamelodica.studentcodeseparator.utils.communication.IncorrectUsageException;

public class SorterExercisePlainJavaTestsRunner
{
	public static void main(String[] args) throws IOException, InterruptedException
	{
		try(WrappedStudentSide exerciseSide = new WrappedStudentSide(args))
		{
			SorterExercisePlainJavaTests.run(exerciseSide.getStudentSide());
		} catch(IncorrectUsageException e)
		{
			e.printStackTrace();
			System.err.println("Usage: java " + SorterExercisePlainJavaTestsRunner.class.getName() + "  " + CommunicationArgsParser.argsSyntax());
		}
	}
}
