package readren.taskflow

import ActorTaskDomain.Procedure

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{Behavior, Scheduler}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.Typeable

object TimedDomain {
	trait TimedAide extends ActorTaskDomain.Aide, TimersExtension.Assistant

	def setup[A: Typeable](frontier: (ActorContext[A], TimedDomain) => Behavior[A]): Behavior[A] = {
		val behaviorA = Behaviors.setup[A] { ctxA =>
		  	Behaviors.withTimers { timerScheduler =>
				val domain: TimedDomain = new TimedDomain(buildTimedAide(ctxA.asInstanceOf[ActorContext[Procedure]], timerScheduler.asInstanceOf[TimerScheduler[Procedure]]));
				frontier(ctxA, domain)
			}
		}

		Behaviors.intercept(ActorTaskDomain.procedureInterceptor)(behaviorA).narrow[A]
	}

	def setup[A: Typeable](ctxA: ActorContext[A], timerScheduler: TimerScheduler[A])(frontier: TimedDomain => Behavior[A]): Behavior[A] = {
		val domain: TimedDomain = new TimedDomain(buildTimedAide(ctxA.asInstanceOf[ActorContext[Procedure]], timerScheduler.asInstanceOf[TimerScheduler[Procedure]]));
		val behaviorA = frontier(domain)
		Behaviors.intercept(ActorTaskDomain.procedureInterceptor)(behaviorA).narrow
	}

	private def buildTimedAide[A >: Procedure](ctx: ActorContext[A], timerScheduler: TimerScheduler[A]): TimedAide = {
		val aide = ActorTaskDomain.buildAide(ctx)
		new TimedAide {
			override def queueForSequentialExecution(runnable: Runnable): Unit = aide.queueForSequentialExecution(runnable)

			override def reportFailure(cause: Throwable): Unit = aide.reportFailure(cause)

			override def scheduler: Scheduler = aide.scheduler

			override def queueForSequentialExecutionDelayed(key: Long, delay: FiniteDuration, runnable: Runnable): Unit = timerScheduler.startSingleTimer(key, Procedure(runnable), delay)

			override def cancelDelayedExecution(key: Long): Unit = timerScheduler.cancel(key)
		}
	}
}


class TimedDomain(assistant: TimedDomain.TimedAide) extends ActorTaskDomain(assistant), TimersExtension(assistant)
