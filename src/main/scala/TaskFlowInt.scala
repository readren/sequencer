package readren.taskflow

import akka.actor.typed.BehaviorInterceptor.{PreStartTarget, ReceiveTarget}
import akka.actor.typed.scaladsl.Behaviors
import akka.event.Logging
import akka.actor.typed.{Behavior, BehaviorInterceptor, TypedActorContext}
import com.typesafe.config.ConfigFactory
import readren.taskflow.TaskFlowInt.RunProcedure

import scala.compiletime.uninitialized
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

object TaskFlowInt {
	private[TaskFlowInt] class RunProcedure(val procedure: () => Unit) extends AnyVal

	val debug: Boolean = ConfigFactory.load().getBoolean(("amigoFutures.debug"))
}
import TaskFlowInt.*

class TaskFlowInt[A](using ctA: ClassTag[A]) extends BehaviorInterceptor[A | RunProcedure, A](classOf[RunProcedure].asInstanceOf[Class[A | RunProcedure]]) {

	private var hiloActualActor: Thread = uninitialized;
	private var ejecutadoPorEsteActorVar: ExecutionContext = uninitialized

	def ejecutadoPorEsteActor: ExecutionContext = ejecutadoPorEsteActorVar

	override def aroundStart(context: TypedActorContext[A | RunProcedure], target: PreStartTarget[A]): Behavior[A] = {
		if debug then hiloActualActor = Thread.currentThread();

		ejecutadoPorEsteActorVar = new ExecutionContext {
			def execute(runnable: Runnable): Unit =
				context.asScala.self ! RunProcedure(() => runnable.run())

			def reportFailure(cause: Throwable): Unit =
				context.asScala.log.error("Reporte de computación asincrónica fallida en actor {}", context.asScala.self, cause);
		}

		target.start(context)
	}

	override def aroundReceive(ctx: TypedActorContext[A | RunProcedure], msg: A | RunProcedure,	target: ReceiveTarget[A]): Behavior[A] = {
		msg match {
			case rp: RunProcedure =>
				rp.procedure()
				Behaviors.same
				
			case a : A => target(ctx, a)
		}
		
	}	

}
