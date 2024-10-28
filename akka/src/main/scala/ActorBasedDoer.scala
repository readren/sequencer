package readren.taskflow.akka

import akka.actor.typed.*
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.util.Timeout
import readren.taskflow.Doer

import scala.reflect.Typeable
import scala.util.{Failure, Success}

object ActorBasedDoer {

	trait Aide extends Doer.Assistant {
		def scheduler: Scheduler
	}

	private[taskflow] case class Procedure(runnable: Runnable)

	def setup[A: Typeable](frontier: (ActorContext[A], ActorBasedDoer) => Behavior[A]): Behavior[A] = {
		val behaviorA = Behaviors.setup[A] { ctx =>
			// TODO analyze if is it OK to upcast the ActorContext in this situation?
			val doer: ActorBasedDoer = new ActorBasedDoer(buildAide(ctx.asInstanceOf[ActorContext[Procedure]]));
			frontier(ctx, doer)
		}

		Behaviors.intercept(procedureInterceptor)(behaviorA).narrow[A]
	}

	def setup[A: Typeable](ctxA: ActorContext[A])(frontier: ActorBasedDoer => Behavior[A]): Behavior[A] = {
		val doer: ActorBasedDoer = new ActorBasedDoer(buildAide(ctxA.asInstanceOf[ActorContext[Procedure]]));
		val behaviorA = frontier(doer)
		Behaviors.intercept(procedureInterceptor)(behaviorA).narrow
	}


	private[taskflow] def buildAide[A >: Procedure](ctx: ActorContext[A]): Aide = new Aide {
		override def queueForSequentialExecution(runnable: Runnable): Unit = ctx.self ! Procedure(runnable)

		override def reportFailure(cause: Throwable): Unit = ctx.log.error("""Error occurred while the actor "{}" was executing a Runnable within a Task.""", ctx.self, cause)
		
		override def scheduler: Scheduler = ctx.system.scheduler
	}

	private[taskflow] def procedureInterceptor[A: Typeable](): BehaviorInterceptor[A | Procedure, A] =
		new BehaviorInterceptor[Procedure, A](classOf[Procedure]) {
			override def aroundReceive(ctxU: TypedActorContext[Procedure], procedure: Procedure, target: BehaviorInterceptor.ReceiveTarget[A]): Behavior[A] = {
				procedure.runnable.run();
				Behaviors.same // TODO: analyze if returning `same` may cause problems in edge cases
			}
		}.asInstanceOf[BehaviorInterceptor[A | Procedure, A]]
}

class ActorBasedDoer(assistant: ActorBasedDoer.Aide) extends Doer(assistant) {

	extension [A](target: ActorRef[A]) {
		def say(message: A): Task[Unit] = Task.mine(() => target ! message)

		/** Note: The type parameter is required for the compiler to know the type parameter of the resulting [[Task]]. */
		def query[B](messageBuilder: ActorRef[B] => A)(using timeout: Timeout): Task[B] = {
			import akka.actor.typed.scaladsl.AskPattern.*
			Task.wait(target.ask[B](messageBuilder)(using timeout, assistant.scheduler))
		}
	}

	extension [A](task: Task[A]) {

		/**
		 * Triggers the execution of this task and sends the result to the `destination`.
		 * 
		 * @param destination the [[ActorRef]] of the actor to send the result to.
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @param errorHandler called if the execution of this task completed with failure.
		 */
		def attemptAndSend(destination: ActorRef[A], isRunningInDoSiThEx: Boolean = false)(errorHandler: Throwable => Unit): Unit = {
			task.attempt(isRunningInDoSiThEx) {
				case Success(r) => destination ! r;
				case Failure(e) => errorHandler(e)
			}
		}
	}
}