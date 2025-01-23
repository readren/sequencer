package readren.taskflow.akka

import akka.actor.typed.*
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.util.Timeout
import readren.taskflow.{AbstractDoer, Doer}

import scala.reflect.Typeable
import scala.util.{Failure, Success}

object ActorBasedDoer {

	trait Aide extends Doer.Assistant {
		def scheduler: Scheduler
	}

	private val currentAssistant: ThreadLocal[Aide] = new ThreadLocal()
	
	private[taskflow] case class Procedure(runnable: Runnable)

	def setup[A: Typeable](ctxA: ActorContext[A])(frontier: ActorBasedDoer => Behavior[A]): Behavior[A] = {
		val aide = buildAide(ctxA.asInstanceOf[ActorContext[Procedure]])
		val doer: ActorBasedDoer = new ActorBasedDoer(aide);
		val behaviorA = frontier(doer)
		val interceptor = buildProcedureInterceptor[A](aide)
		Behaviors.intercept(() => interceptor)(behaviorA).narrow
	}


	private[taskflow] def buildAide[A >: Procedure](ctx: ActorContext[A]): Aide = new Aide {
		override def executeSequentially(runnable: Runnable): Unit = ctx.self ! Procedure(runnable)

		override def current: Aide = currentAssistant.get

		override def reportFailure(cause: Throwable): Unit = ctx.log.error("""Error occurred while the actor "{}" was executing a Runnable within a Task.""", ctx.self, cause)
		
		override def scheduler: Scheduler = ctx.system.scheduler
	}

	private[taskflow] def buildProcedureInterceptor[A](aide: Aide): BehaviorInterceptor[A | Procedure, A] =
		new BehaviorInterceptor[Any, A](classOf[Any]) {
			override def aroundReceive(ctxU: TypedActorContext[Any], message: Any, target: BehaviorInterceptor.ReceiveTarget[A]): Behavior[A] = {
				currentAssistant.set(aide)
				try {
					message match {
						case procedure: Procedure =>
							procedure.runnable.run()
							Behaviors.same // TODO: analyze if returning `same` may cause problems in edge cases
							
						case a: A @unchecked =>
							target(ctxU, a)
					}
				} finally currentAssistant.remove()
			}
		}.asInstanceOf[BehaviorInterceptor[A | Procedure, A]]
}

class ActorBasedDoer(aide: ActorBasedDoer.Aide) extends AbstractDoer {
	override type Assistant = ActorBasedDoer.Aide
	override val assistant: Assistant = aide

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
		 * @param isWithinDoSiThEx $isRunningInDoSiThEx
		 * @param errorHandler called if the execution of this task completed with failure.
		 */
		def attemptAndSend(destination: ActorRef[A], isWithinDoSiThEx: Boolean = assistant.isWithinDoSiThEx)(errorHandler: Throwable => Unit): Unit = {
			task.trigger(isWithinDoSiThEx) {
				case Success(r) => destination ! r;
				case Failure(e) => errorHandler(e)
			}
		}
	}
}