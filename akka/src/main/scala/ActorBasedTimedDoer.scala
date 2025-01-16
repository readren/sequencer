package readren.taskflow.akka

import ActorBasedDoer.Procedure

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{Behavior, Scheduler}
import readren.taskflow.TimersExtension

import scala.concurrent.duration.FiniteDuration
import scala.reflect.Typeable

object ActorBasedTimedDoer {
	trait TimedAide extends ActorBasedDoer.Aide, TimersExtension.Assistant

	private val currentTimedAide: ThreadLocal[TimedAide] = new ThreadLocal()
	
	def setup[A: Typeable](ctxA: ActorContext[A], timerScheduler: TimerScheduler[A])(frontier: ActorBasedTimedDoer => Behavior[A]): Behavior[A] = {
		val aide = buildTimedAide(ctxA.asInstanceOf[ActorContext[Procedure]], timerScheduler.asInstanceOf[TimerScheduler[Procedure]])
		val doer: ActorBasedTimedDoer = new ActorBasedTimedDoer(aide);
		val behaviorA = frontier(doer)
		val interceptor = ActorBasedDoer.buildProcedureInterceptor[A](aide)
		Behaviors.intercept(() => interceptor)(behaviorA).narrow
	}

	private def buildTimedAide[A >: Procedure](ctx: ActorContext[A], timerScheduler: TimerScheduler[A]): TimedAide = {
		val aide = ActorBasedDoer.buildAide(ctx)
		new TimedAide {
			override def queueForSequentialExecution(runnable: Runnable): Unit = aide.queueForSequentialExecution(runnable)

			override def current: TimedAide = currentTimedAide.get

			override def reportFailure(cause: Throwable): Unit = aide.reportFailure(cause)

			override def scheduler: Scheduler = aide.scheduler

			override def queueForSequentialExecutionDelayed(key: Long, delay: FiniteDuration, runnable: Runnable): Unit = timerScheduler.startSingleTimer(key, Procedure(runnable), delay)

			override def cancelDelayedExecution(key: Long): Unit = timerScheduler.cancel(key)
		}
	}
}


class ActorBasedTimedDoer(timedAide: ActorBasedTimedDoer.TimedAide) extends ActorBasedDoer(timedAide), TimersExtension {
	override val timedAssistant: ActorBasedTimedDoer.TimedAide = timedAide
}
