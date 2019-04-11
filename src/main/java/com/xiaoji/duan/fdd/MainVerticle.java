package com.xiaoji.duan.fdd;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Calendar;
import java.util.HashMap;

import org.apache.commons.codec.binary.Base64;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TemplateHandler;
import io.vertx.ext.web.templ.thymeleaf.ThymeleafTemplateEngine;

public class MainVerticle extends AbstractVerticle {

	private ThymeleafTemplateEngine thymeleaf = null;
	private MongoClient mongodb = null;
	private WebClient client = null;

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		client = WebClient.create(vertx);

		thymeleaf = ThymeleafTemplateEngine.create(vertx);
		TemplateHandler templatehandler = TemplateHandler.create(thymeleaf);

		StringTemplateResolver resolver = new StringTemplateResolver();
		resolver.setCacheable(false);
		resolver.setTemplateMode("HTML5");
		resolver.setCacheTTLMs(1000L);
		thymeleaf.getThymeleafTemplateEngine().setTemplateResolver(resolver);

//		JsonObject config = new JsonObject();
//		config.put("host", "mongodb");
//		config.put("port", 27017);
//		config.put("keepAlive", true);
//		mongodb = MongoClient.createShared(vertx, config);

		Router router = Router.router(vertx);

		router.route().handler(CorsHandler.create("*").allowedHeader("*"));

		StaticHandler staticfiles = StaticHandler.create().setCachingEnabled(true).setWebRoot("static");
		router.route("/fdd/static/*").handler(staticfiles);
		router.route("/fdd").pathRegex("\\/.+\\.json").handler(staticfiles);

		router.route("/fdd/thymeleaf/:html/json/:data").handler(BodyHandler.create());
		router.route("/fdd/thymeleaf/:html/json/:data").handler(this::thymeleafshowwithjson);
		
		router.route("/fdd/thymeleaf/:html/json/:data").handler(ctx -> {
			JsonObject data = (JsonObject) ctx.get("data");
			Map<String, Object> push = new HashMap<String, Object>();
			try {
				if (data != null)
					push = data.getMap();
				push.put("minfontcode", Base64.encodeBase64URLSafeString(ctx.request().path().getBytes()));
				push.put("today", Calendar.getInstance());
			} catch (Exception e) {
				e.printStackTrace(); 
			}
			thymeleaf.render(push, ctx.get("html"), res -> {
				if (res.succeeded()) {
					ctx.response().putHeader("Content-Type", "text/html").end(res.result());
				} else {
					ctx.fail(res.cause());
				}
			});
		});
		
		HttpServerOptions option = new HttpServerOptions();
		option.setCompressionSupported(true);

		vertx.createHttpServer(option).requestHandler(router::accept).listen(8080, http -> {
			if (http.succeeded()) {
				startFuture.complete();
				System.out.println("HTTP server started on http://localhost:8080");
			} else {
				startFuture.fail(http.cause());
			}
		});
	}

	private void thymeleafshowwithjson(RoutingContext ctx) {
		String html = ctx.request().getParam("html");
		String data = ctx.request().getParam("data");
		
		List<Future<JsonObject>> futures = new LinkedList<Future<JsonObject>>();
		
		Future<JsonObject> htmlFuture = Future.future();
		futures.add(htmlFuture);
		
		client.get(8080, "sa-abl", "/abl/store/local/getContent/" + html)
		.send(htmlHandler -> {
			if (htmlHandler.succeeded()) {
				htmlFuture.complete(new JsonObject().put("html", htmlHandler.result().bodyAsString()));
			} else {
				htmlFuture.fail(htmlHandler.cause());
			}
		});
		
		Future<JsonObject> dataFuture = Future.future();
		futures.add(dataFuture);
		
		client.get(8080, "sa-abl", "/abl/store/local/getContent/" + data)
		.send(dataHandler -> {
			if (dataHandler.succeeded()) {
				dataFuture.complete(new JsonObject().put("data", dataHandler.result().bodyAsJsonObject()));
			} else {
				dataFuture.fail(dataHandler.cause());
			}
		});
		
		CompositeFuture.all(Arrays.asList(futures.toArray(new Future[futures.size()])))
		.map(v -> futures.stream().map(Future::result).collect(Collectors.toList()))
		.setHandler(handler -> {
			if (handler.succeeded()) {
				List<JsonObject> result = handler.result();
				
				String fetchedhtml = "";
				JsonObject fetcheddata = new JsonObject();
				
				for (JsonObject one : result) {
					if (one.containsKey("html")) {
						fetchedhtml = one.getString("html");
					}

					if (one.containsKey("data")) {
						fetcheddata = one.getJsonObject("data");
					}
				}
				
				ctx.put("html", fetchedhtml);
				ctx.put("data", fetcheddata);
				ctx.next();
			} else {
				ctx.response().putHeader("Content-Type", "text/plain").end(handler.cause().getMessage());
			}
		});
	}
}
