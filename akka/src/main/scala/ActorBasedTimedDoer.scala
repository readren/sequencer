package readren.taskflow.akka

import ActorBasedDoer.Procedure

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{Behavior, Scheduler}
import readren.taskflow.TimersExtension

import scala.concurrent.duration.FiniteDuration
import scala.reflect.Typeable

object ActorBasedTimedDoer {
	trait TimedAide extends ActorBasedDoer.Aide, TimersExtension.Assistant

	def setup[A: Typeable](frontier: (ActorContext[A], ActorBasedTimedDoer) => Behavior[A]): Behavior[A] = {
		val behaviorA = Behaviors.setup[A] { ctxA =>
		  	Behaviors.withTimers { timerScheduler =>
				val Doer: ActorBasedTimedDoer = new ActorBasedTimedDoer(buildTimedAide(ctxA.asInstanceOf[ActorContext[Procedure]], timerScheduler.asInstanceOf[TimerScheduler[Procedure]]));
				frontier(ctxA, Doer)
			}
		}

		Behaviors.intercept(ActorBasedDoer.procedureInterceptor)(behaviorA).narrow[A]
	}

	def setup[A: Typeable](ctxA: ActorContext[A], timerScheduler: TimerScheduler[A])(frontier: ActorBasedTimedDoer => Behavior[A]): Behavior[A] = {
		val doer: ActorBasedTimedDoer = new ActorBasedTimedDoer(buildTimedAide(ctxA.asInstanceOf[ActorContext[Procedure]], timerScheduler.asInstanceOf[TimerScheduler[Procedure]]));
		val behaviorA = frontier(doer)
		Behaviors.intercept(ActorBasedDoer.procedureInterceptor)(behaviorA).narrow
	}

	private def buildTimedAide[A >: Procedure](ctx: ActorContext[A], timerScheduler: TimerScheduler[A]): TimedAide = {
		val aide = ActorBasedDoer.buildAide(ctx)
		new TimedAide {
			override def queueForSequentialExecution(runnable: Runnable): Unit = aide.queueForSequentialExecution(runnable)

			override def reportFailure(cause: Throwable): Unit = aide.reportFailure(cause)

			override def scheduler: Scheduler = aide.scheduler

			override def queueForSequentialExecutionDelayed(key: Long, delay: FiniteDuration, runnable: Runnable): Unit = timerScheduler.startSingleTimer(key, Procedure(runnable), delay)

			override def cancelDelayedExecution(key: Long): Unit = timerScheduler.cancel(key)
		}
	}
}


class ActorBasedTimedDoer(assistant: ActorBasedTimedDoer.TimedAide) extends ActorBasedDoer(assistant), TimersExtension(assistant)
