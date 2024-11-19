package readren.taskflow

import Doer.Assistant

import scala.quoted.{Expr, Quotes, Type}
import scala.util.Try

object DoerMacros {


	def triggerImpl[A: Type](isRunningInDoSiThExExpr: Expr[Boolean], assistantExpr: Expr[Doer.Assistant], dutyExpr: Expr[Doer#Duty[A]], onCompleteExpr: Expr[A => Unit])(using quotes: Quotes): Expr[Unit] = {
		import quotes.reflect.*

		def runnable: Expr[Runnable] = {
			val pos: Position = onCompleteExpr.asTerm.pos;
			val sourceInfo: Expr[String] = Expr(s".engage(${Printer.TreeShortCode.show(onCompleteExpr.asTerm)}) } @ ${pos.sourceFile.name}:${pos.startLine + 1}")

			'{
				new Runnable {
					override def run(): Unit = $dutyExpr.engagePortal($onCompleteExpr)

					override def toString: String = s"{ ${$dutyExpr.toString}${$sourceInfo}"
				}
			}
		}

		isRunningInDoSiThExExpr.value match {
			case Some(isRunningInDoSiThEx) =>
				if isRunningInDoSiThEx then '{ $dutyExpr.engagePortal($onCompleteExpr) }
				else '{ $assistantExpr.queueForSequentialExecution($runnable) }

			case None =>
				'{
					if $isRunningInDoSiThExExpr then $dutyExpr.engagePortal($onCompleteExpr)
					else $assistantExpr.queueForSequentialExecution($runnable)
				}
		}
	}

	def queueForSequentialExecutionImpl(assistantExpr: Expr[Assistant], procedureExpr: Expr[Unit])(using quotes: Quotes): Expr[Unit] = {
		import quotes.reflect.*

		// Capture the source code location
		val pos: Position = procedureExpr.asTerm.pos;
		// Build source info text.
		val sourceInfo = Expr(s"{ ${procedureExpr.asTerm.show} } @ ${pos.sourceFile.name}:${pos.startLine + 1}")
		//		val sourceInfo = Expr(s"{ ${pos.sourceCode.getOrElse("not available")} } @ ${pos.sourceFile.name}:${pos.startLine + 1}")

		val runnable: Expr[Runnable] = '{
			new Runnable {
				override def run(): Unit = $procedureExpr

				override def toString: String = $sourceInfo
			}
		}
		// Call the assistant's method with the new wrapped Runnable
		'{ $assistantExpr.queueForSequentialExecution($runnable) }
	}

	def reportFailureImpl(assistantExpr: Expr[Assistant], causeExpr: Expr[Throwable])(using quotes: Quotes): Expr[Unit] = {
		import quotes.reflect.*
		// Capture the source code location.
		val pos = Position.ofMacroExpansion
		// Get the source code snippet from the source file at the specific line.
		val snippet = pos.sourceCode.getOrElse("Source code not available")
		// Build exception message.
		val message = Expr(s"Reported at ${pos.sourceFile.name}:${pos.startLine + 1} => $snippet")

		'{ $assistantExpr.reportFailure(new Doer.ExceptionReport($message, $causeExpr)) }
	}
}
