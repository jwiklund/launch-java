package com.spotify.launch_java;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

public class LaunchJava {
  private JavaCompiler compiler;

  public LaunchJava(JavaCompiler compiler) {
    this.compiler = compiler;
  }

  public static void main(String[] args) {
    Stopwatch sw = Stopwatch.createStarted();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      System.out.println("Could not find java compiler, launcher needs jdk");
      System.exit(1);
    }

    LaunchJava launcher = new LaunchJava(compiler);
    launcher.run("System.out.println(\"Hello\");", args);
    System.out.printf("time %d\n", sw.elapsed(TimeUnit.MILLISECONDS));
  }

  private void run(String statements, String[] args) {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
    Iterable<? extends JavaFileObject> compilationUnits = Arrays.asList(new JavaSourceFromString("Main", wrapp(statements)));
    JavaMemFileManager files = new JavaMemFileManager(compiler);
    CompilationTask task = compiler.getTask(null, files, diagnostics, null, null, compilationUnits);
    if (!task.call()) {
      for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
        System.out.println(diag.toString());
      }
      return;
    }
    try (MemoryClassLoader loader = new MemoryClassLoader(files, getClass().getClassLoader())) {
      Class<?> loaded = loader.loadClass("Main");
      Method method = loaded.getMethod("main", new Class[] { String[].class });
      method.invoke(null, (Object) args);
    } catch (Exception e) {
      Throwables.propagate(e);
    }
  }

  private String wrapp(String statements) {
    URL resource = Resources.getResource("Main.java.template");
    CharSource template = Resources.asCharSource(resource, Charsets.UTF_8);
    try {
      return String.format(template.read(), statements);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  class JavaSourceFromString extends SimpleJavaFileObject {
    final String code;

    JavaSourceFromString(String name, String code) {
      super(URI.create("string:///" + name.replace('.','/') + Kind.SOURCE.extension),Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return code;
    }
  }

  class JavaMemFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    class ClassMemFileObject extends SimpleJavaFileObject {
      ByteArrayOutputStream os = new ByteArrayOutputStream();

      ClassMemFileObject(String className) {
        super(URI.create("mem:///" + className + Kind.CLASS.extension), Kind.CLASS);
      }

      byte[] getBytes() {
        return os.toByteArray();
      }

      @Override
      public OutputStream openOutputStream() throws IOException {
        return os;
      }
    }

    private HashMap<String, ClassMemFileObject> classes = new HashMap<String, ClassMemFileObject>();

    public JavaMemFileManager(JavaCompiler compiler) {
      super(compiler.getStandardFileManager(null, null, null));
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling) throws IOException {
      if (StandardLocation.CLASS_OUTPUT == location && JavaFileObject.Kind.CLASS == kind) {
        ClassMemFileObject clazz = new ClassMemFileObject(className);
        classes.put(className, clazz);
        return clazz;
      } else {
        return super.getJavaFileForOutput(location, className, kind, sibling);
      }
    }

    public byte[] getClassBytes(String className) {
      if (classes.containsKey(className)) {
        return classes.get(className).getBytes();
      }
      return null;
    }
  }

  class MemoryClassLoader extends URLClassLoader {

    private JavaMemFileManager files;

    public MemoryClassLoader(JavaMemFileManager files, ClassLoader parent) {
      super(new URL[0], parent);
      this.files = files;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      byte[] classBytes = files.getClassBytes(name);
      if (classBytes != null) {
        return defineClass(name, classBytes, 0, classBytes.length);
      }
      return super.findClass(name);
    }
  }
}
