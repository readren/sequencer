package readren.taskflow

import akka.actor.typed.{Behavior, ExtensibleBehavior, Signal, TypedActorContext}

class TaskFlowBeh[A] extends ExtensibleBehavior[A] {

	override def receive(ctx: TypedActorContext[A], msg: A): Behavior[A] = ???

	override def receiveSignal(ctx: TypedActorContext[A], msg: Signal): Behavior[A] = ???
}
