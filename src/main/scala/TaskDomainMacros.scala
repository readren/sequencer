package readren.taskflow

import TaskDomain.Assistant

import java.nio.file.{Files, Paths}
import scala.quoted.{Expr, Quotes}

object TaskDomainMacros {
	def queueForSequentialExecutionImpl(assistantExpr: Expr[Assistant], procedureExpr: Expr[Unit])(using quotes: Quotes): Expr[Unit] = {
		val sourceInfo = '{ "Executed code is at line: " + $getSourceInfo }

		val runnable: Expr[Runnable] = '{
			new Runnable {
				override def run(): Unit = {
					$procedureExpr
				}

				override def toString: String = {
					$sourceInfo
				}
			}
		}
		// Call the assistant's method with the new wrapped Runnable
		'{ $assistantExpr.queueForSequentialExecution($runnable) }

	}

	def reportFailureImpl(assistantExpr: Expr[Assistant], causeExpr: Expr[Throwable])(using quotes: Quotes): Expr[Unit] = {
		val message = '{ "Exception reported from line: " + $getSourceInfo }
		val exceptionReport = '{ new TaskDomain.ExceptionReport($message, $causeExpr) }

		'{ $assistantExpr.reportFailure($exceptionReport) }
	}

	private def getSourceInfo(using quotes: Quotes): Expr[String] = {
		import quotes.reflect.*

		// Capture the source code location
		val pos = Position.ofMacroExpansion

		// Get the source code snippet from the source file at the specific line
		val sourceCodeLine: String = try {
			val lines = Files.readAllLines(Paths.get(pos.sourceFile.path))
			lines.get(pos.startLine).trim
		} catch {
			case _: Exception => "Source code not available"
		}

		Expr(s"${pos.sourceFile}:${pos.startLine + 1} => $sourceCodeLine")
	}

}
