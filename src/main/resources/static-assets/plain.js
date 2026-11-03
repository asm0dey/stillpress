document.addEventListener('click', function (e) {
  var b = e.target.closest('.sp-yt-facade');
  if (!b) return;
  var f = document.createElement('iframe');
  f.src = 'https://www.youtube-nocookie.com/embed/' + encodeURIComponent(b.dataset.ytId) + '?autoplay=1';
  f.allow = 'autoplay; encrypted-media; picture-in-picture';
  f.allowFullscreen = true;
  f.style.cssText = 'width:100%;aspect-ratio:16/9;border:0';
  b.replaceWith(f);
});
