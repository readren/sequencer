package readren.taskflow

import akka.actor.typed.scaladsl.{Behaviors, LoggerOps}
import akka.actor.typed.*


object Test {

	@main def run(): Unit = {
			val system = ActorSystem(TestActor.apply, "Test")

		}

		object TestActor {

			case class Pregunta(replyTo: ActorRef[Respuesta], text: String) {
//				println(s"creando pregunta: replyTo=$replyTo, text=$text")
			}

			case class Respuesta(replyTo: ActorRef[Pregunta], text: String)

			def apply: Behavior[Pregunta] = {
				Behaviors.setup { actorContext =>
					AkkaIntegration.setup(actorContext) { taskContext =>
						Behaviors.receiveMessage { pregunta =>
							taskContext.Task.successful(Respuesta(actorContext.self, "Hola")).attemptAndSend(true, pregunta.replyTo)

							Behaviors.same
						}
					}
				}
			}
		}




}
