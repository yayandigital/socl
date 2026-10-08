"""Sisipkan dua kait smali: registrasi plugin SYExt di MainActivity dan ExtRuntime.attach di DolaWebActivity."""
import sys
root = sys.argv[1]  # .../smali/com/studioyanz/app

def patch(path, old, new):
    s = open(path, encoding='utf-8').read()
    if new in s:
        print('sudah dipatch:', path); return
    assert s.count(old) == 1, ('pola tidak unik/tidak ada', path)
    open(path, 'w', encoding='utf-8').write(s.replace(old, new))
    print('patched:', path)

patch(root + '/MainActivity.smali',
      '    invoke-super {p0, p1}, Lcom/getcapacitor/BridgeActivity;->onCreate(Landroid/os/Bundle;)V',
      '    const-class v0, Lcom/studioyanz/app/ext/ExtensionsPlugin;\n\n'
      '    invoke-virtual {p0, v0}, Lcom/studioyanz/app/MainActivity;->registerPlugin(Ljava/lang/Class;)V\n\n'
      '    invoke-super {p0, p1}, Lcom/getcapacitor/BridgeActivity;->onCreate(Landroid/os/Bundle;)V')

patch(root + '/plugins/DolaWebActivity.smali',
      '    iget-object v0, p0, Lcom/studioyanz/app/plugins/DolaWebActivity;->web:Landroid/webkit/WebView;\n\n'
      '    invoke-virtual {v0, p1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V',
      '    iget-object v0, p0, Lcom/studioyanz/app/plugins/DolaWebActivity;->web:Landroid/webkit/WebView;\n\n'
      '    invoke-static {p0, v0}, Lcom/studioyanz/app/ext/ExtRuntime;->attach(Landroid/app/Activity;Landroid/webkit/WebView;)V\n\n'
      '    invoke-virtual {v0, p1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V')
