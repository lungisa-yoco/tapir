package tapir.docs.openapi

import tapir.docs.openapi.schema.ObjectSchemas
import tapir.openapi.OpenAPI.ReferenceOr
import tapir.openapi.{MediaType => OMediaType, _}
import tapir.{EndpointInput, MediaType => SMediaType, _}

private[openapi] class EndpointToOpenApiPaths(objectSchemas: ObjectSchemas, options: OpenAPIDocsOptions) {

  def pathItem(e: Endpoint[_, _, _]): (String, PathItem) = {
    import Method._

    val pathComponents = foldInputToVector(e.input, {
      case EndpointInput.PathCapture(_, name, _) => s"{${name.getOrElse("-")}}"
      case EndpointInput.PathSegment(s)          => s
    })

    val defaultId = options.operationIdGenerator(pathComponents, e.method)

    val operation = Some(endpointToOperation(defaultId, e))
    val pathItem = PathItem(
      None,
      None,
      get = if (e.method == GET) operation else None,
      put = if (e.method == PUT) operation else None,
      post = if (e.method == POST) operation else None,
      delete = if (e.method == DELETE) operation else None,
      options = if (e.method == OPTIONS) operation else None,
      head = if (e.method == HEAD) operation else None,
      patch = if (e.method == PATCH) operation else None,
      trace = if (e.method == TRACE) operation else None,
      servers = List.empty,
      parameters = List.empty
    )

    ("/" + pathComponents.mkString("/"), pathItem)
  }

  private def endpointToOperation(defaultId: String, e: Endpoint[_, _, _]): Operation = {
    val parameters = operationParameters(e)
    val body: Vector[ReferenceOr[RequestBody]] = operationInputBody(e)
    val responses: Map[ResponsesKey, ReferenceOr[Response]] = operationResponse(e)
    Operation(
      e.info.tags.toList,
      e.info.summary,
      e.info.description,
      defaultId,
      parameters.toList.map(Right(_)),
      body.headOption,
      responses,
      None,
      List.empty
    )
  }

  private def operationInputBody(e: Endpoint[_, _, _]) = {
    foldInputToVector(
      e.input, {
        case EndpointIO.Body(codec, info) =>
          Right(RequestBody(info.description, codecToMediaType(codec, info.example), Some(!codec.meta.isOptional)))
      }
    )
  }

  private def operationParameters(e: Endpoint[_, _, _]) = {
    foldInputToVector(
      e.input, {
        case q: EndpointInput.Query[_]       => queryToParameter(q)
        case p: EndpointInput.PathCapture[_] => pathCaptureToParameter(p)
        case h: EndpointIO.Header[_]         => headerToParameter(h)
      }
    )
  }

  private def headerToParameter[T](header: EndpointIO.Header[T]) = {
    EndpointInputToParameterConverter.from(header,
                                           objectSchemas(header.codec.meta.schema),
                                           header.info.example.flatMap(exampleValue(header.codec, _)))
  }
  private def pathCaptureToParameter[T](p: EndpointInput.PathCapture[T]) = {
    EndpointInputToParameterConverter.from(p, objectSchemas(p.codec.meta.schema), p.info.example.flatMap(exampleValue(p.codec, _)))
  }

  private def queryToParameter[T](query: EndpointInput.Query[T]) = {
    EndpointInputToParameterConverter.from(query,
                                           objectSchemas(query.codec.meta.schema),
                                           query.info.example.flatMap(exampleValue(query.codec, _)))
  }

  private def operationResponse(e: Endpoint[_, _, _]): Map[ResponsesKey, Right[Nothing, Response]] = {
    List(
      outputToResponse(e.output).map { r =>
        ResponsesCodeKey(200) -> Right(r)
      },
      outputToResponse(e.errorOutput).map { r =>
        ResponsesDefaultKey -> Right(r)
      }
    ).flatten.toMap
  }

  private def outputToResponse(io: EndpointIO[_]): Option[Response] = {
    val headers = foldIOToVector(
      io, {
        case EndpointIO.Header(name, codec, info) =>
          name -> Right(
            Header(
              info.description,
              Some(!codec.meta.isOptional),
              None,
              None,
              None,
              None,
              None,
              Some(objectSchemas(codec.meta.schema)),
              info.example.flatMap(exampleValue(codec, _)),
              Map.empty,
              Map.empty
            ))
      }
    )

    val bodies = foldIOToVector(io, {
      case EndpointIO.Body(m, info) => (info.description, codecToMediaType(m, info.example))
    })
    val body = bodies.headOption

    val description = body.flatMap(_._1).getOrElse("")
    val content = body.map(_._2).getOrElse(Map.empty)

    if (body.isDefined || headers.nonEmpty) {
      Some(Response(description, headers.toMap, content))
    } else {
      None
    }
  }

  private def codecToMediaType[T, M <: SMediaType](o: GeneralCodec[T, M, _], example: Option[T]): Map[String, OMediaType] = {
    Map(
      o.meta.mediaType.mediaTypeNoParams -> OMediaType(Some(objectSchemas(o.meta.schema)),
                                                       example.flatMap(exampleValue(o, _)),
                                                       Map.empty,
                                                       Map.empty))
  }

  private def exampleValue[T](codec: GeneralCodec[T, _, _], e: T): Option[ExampleValue] =
    codec.encodeOptional(e).map(v => ExampleValue(v.toString))

  private def foldInputToVector[T](i: EndpointInput[_], f: PartialFunction[EndpointInput[_], T]): Vector[T] = {
    i match {
      case _ if f.isDefinedAt(i)                  => Vector(f(i))
      case EndpointInput.Mapped(wrapped, _, _, _) => foldInputToVector(wrapped, f)
      case EndpointIO.Mapped(wrapped, _, _, _)    => foldInputToVector(wrapped, f)
      case EndpointInput.Multiple(inputs)         => inputs.flatMap(foldInputToVector(_, f))
      case EndpointIO.Multiple(inputs)            => inputs.flatMap(foldInputToVector(_, f))
      case _                                      => Vector.empty
    }
  }

  private def foldIOToVector[T](io: EndpointIO[_], f: PartialFunction[EndpointIO[_], T]): Vector[T] = {
    io match {
      case _ if f.isDefinedAt(io)              => Vector(f(io))
      case EndpointIO.Mapped(wrapped, _, _, _) => foldIOToVector(wrapped, f)
      case EndpointIO.Multiple(inputs)         => inputs.flatMap(foldIOToVector(_, f))
      case _                                   => Vector.empty
    }
  }
}