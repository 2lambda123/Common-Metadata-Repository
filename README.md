# Clojang Codox Theme

This is a modern and responsive [codox][codox] theme development for
Clojang project, based on the
[codox-theme-rdash project](https://github.com/xsc/codox-theme-rdash).

<img src='screenshots/rdash.jpg' alt='Codox + RDash' />

Note that this needs codox ≥ 0.10.0.

[codox]: https://github.com/weavejester/codox
[rdash]: http://rdash.github.io/

## Usage

Add the following dependency to your `project.clj`:

[![Clojars Project](https://img.shields.io/clojars/v/codox-theme-rdash.svg)](https://clojars.org/codox-theme-rdash)

Then set the following:

```clojure
:codox {:themes [:clojang]}
```

For syntax highlighting capabilities, you'll need to activate Markdown rendering
via:

```clojure
:codox {:metadata {:doc/format :markdown}
        :themes [:rdash]}
```

## Examples

- [xsc/claro](https://xsc.github.io/claro/)
- [xsc/iapetos](https://xsc.github.io/iapetos/index.html)
- [xsc/invariant](https://xsc.github.io/invariant/index.html)
- [xsc/kithara](https://xsc.github.io/kithara/index.html)

## License

Copyright &copy; 2017 Duncan McGreggor
Copyright &copy; 2016 Yannick Scherer

This project is licensed under the [Eclipse Public License 1.0][license].

[license]: https://www.eclipse.org/legal/epl-v10.html

## Derivative

This theme is based on the codox default assets licensed under the
[Eclipse Public License v1.0][epl]:

```
Copyright (c) 2016 James Reeves
```

[epl]: http://www.eclipse.org/legal/epl-v10.html

The RDash UI assets are licensed under the MIT license:

```
The MIT License (MIT)

Copyright (c) 2014 rdash

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
