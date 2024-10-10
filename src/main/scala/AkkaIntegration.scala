package readren.taskflow

import akka.actor.typed.*
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.util.Timeout

import scala.reflect.Typeable
import scala.util.{Failure, Success}

object AkkaIntegration {

	trait ExtendedAssistant extends TaskDomain.Assistant {
		def scheduler: Scheduler
	}
	
	class TaskDomainExtension(assistant: ExtendedAssistant) extends TaskDomain(assistant) {

		extension [A](target: ActorRef[A]) {
			def say(message: A): Task[Unit] = Task.mine(() => target ! message)
			
			/** Note: The type parameter is required for the compiler to know the type parameter of the resulting [[Task]]. */
			def query[B](messageBuilder: ActorRef[B] => A)(using timeout: Timeout): Task[B] = {
				import akka.actor.typed.scaladsl.AskPattern.*
				Task.alien(target.ask[B](messageBuilder)(using timeout, assistant.scheduler))
			}
		}

		extension [A](task: Task[A]) {
			/** $cuandoCompletaEjecutadaPorEsteActor
			 * $desdeCualquierHilo
			 */
			def attemptAndSend(isRunningInDoSiThEx: Boolean, destination: ActorRef[A])(errorHandler: Throwable => Unit): Unit = {
				task.attempt(isRunningInDoSiThEx) {
					case Success(r) => destination ! r;
					case Failure(e) => errorHandler(e)
				}
			}
		}
	}


	private[AkkaIntegration] case class Procedure(runnable: Runnable)

	def setup[A: Typeable](frontier: (ActorContext[A], TaskDomainExtension) => Behavior[A]): Behavior[A] = {
		val behaviorA = Behaviors.setup[A] { ctx =>
			// TODO analyze if is it OK to upcast the ActorContext in this situation?
			val domain: TaskDomainExtension = new TaskDomainExtension(buildAssistant(ctx.asInstanceOf[ActorContext[Procedure]]));
			frontier(ctx, domain)
		}

		val behaviorU = Behaviors.intercept(procedureInterceptor)(behaviorA)
		behaviorU.narrow[A]
	}

	def setup[A: Typeable](ctxA: ActorContext[A])(frontier: TaskDomainExtension => Behavior[A]): Behavior[A] = {
		val domain: TaskDomainExtension = new TaskDomainExtension(buildAssistant(ctxA.asInstanceOf[ActorContext[Procedure]]));
		val behaviorA = frontier(domain)
		Behaviors.intercept(procedureInterceptor)(behaviorA).narrow
	}


	private def buildAssistant[A >: Procedure](ctx: ActorContext[A]): ExtendedAssistant = new ExtendedAssistant {
		override def queueForSequentialExecution(runnable: Runnable): Unit = ctx.self ! Procedure(runnable)

		override def reportFailure(cause: Throwable): Unit = ctx.log.error("""Error occurred while the actor "{}" was executing a Runnable within a Task.""", ctx.self, cause)
		
		override def scheduler: Scheduler = ctx.system.scheduler
	}

	private def procedureInterceptor[A: Typeable](): BehaviorInterceptor[A | Procedure, A] =
		new BehaviorInterceptor[Procedure, A](classOf[Procedure]) {
			override def aroundReceive(ctxU: TypedActorContext[Procedure], procedure: Procedure, target: BehaviorInterceptor.ReceiveTarget[A]): Behavior[A] = {
				procedure.runnable.run();
				Behaviors.same // TODO: analyze if returning `same` may cause problems in edge cases
			}
		}.asInstanceOf[BehaviorInterceptor[A | Procedure, A]]
}
