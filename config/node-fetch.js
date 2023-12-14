const fetch = require('node-fetch');

// Now you can use fetch as usual
fetch('http://example.com')
  .then(response => response.json())
  .then(data => console.log(data))
  .catch(error => console.error('Error:', error));

